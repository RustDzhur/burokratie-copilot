package de.buerokratiecopilot.agents.banking;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentInputs;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentWorkspace;
import de.buerokratiecopilot.banking.CamtParser;
import de.buerokratiecopilot.banking.PaymentMatchingService;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code banking-reconcile} — Bankabgleich.
 *
 * <p>Reads unmatched bank movements in a period and asks the banking module's matching rules for
 * candidates. A unique candidate is recorded as a <strong>proposal</strong> ({@code PROPOSED},
 * {@code SUGGESTED}) and a task asks a person to confirm it. Ambiguous candidates are recorded with
 * their reasons and left open. No payment is booked by this agent — the only booking method in the
 * module belongs to a confirmed human decision.</p>
 *
 * <p>Idempotent through the schema's unique match pair and the task's {@code agentKey}; a repeated
 * run proposes nothing twice.</p>
 */
@Component
public class BankingReconcileAgent implements Agent {

    private static final int MAX_TRANSACTIONS = 200;
    private static final int MAX_TASKS = 50;

    private final JdbcTemplate jdbc;
    private final PaymentMatchingService matching;
    private final AgentWorkspace workspace;

    public BankingReconcileAgent(JdbcTemplate jdbc, PaymentMatchingService matching,
                                 AgentWorkspace workspace) {
        this.jdbc = jdbc;
        this.matching = matching;
        this.workspace = workspace;
    }

    @Override
    public String id() {
        return "banking-reconcile";
    }

    @Override
    public AgentResult run(AgentContext context) {
        LocalDate from = AgentInputs.date(context.input("periodFrom"));
        LocalDate to = AgentInputs.date(context.input("periodTo"));
        if (from == null || to == null || to.isBefore(from)) {
            return AgentResult.skipped("Bankabgleich: 'periodFrom' und 'periodTo' sind als gültiger "
                    + "Zeitraum erforderlich.");
        }
        UUID accountId = AgentInputs.uuid(context.input("accountId"));
        if (context.input("accountId") != null && !context.input("accountId").isBlank()
                && accountId == null) {
            return AgentResult.failed("Bankabgleich: 'accountId' ist keine gültige UUID.");
        }
        BigDecimal minAmount = AgentInputs.decimal(context.input("minAmount"));
        if (context.input("minAmount") != null && !context.input("minAmount").isBlank()
                && minAmount == null) {
            return AgentResult.failed("Bankabgleich: 'minAmount' ist keine gültige Dezimalzahl.");
        }
        int toleranceCents = AgentInputs.integer(context.input("toleranceCents"), 0, 0, 10_000);

        StringBuilder sql = new StringBuilder("""
                select id, booking_date, value_date, amount, currency, counterparty_name,
                       counterparty_iban, purpose, reference, end_to_end_id, transaction_type,
                       external_id
                from bank_transactions
                where tenant_id = ? and status = 'UNMATCHED'
                  and booking_date between ? and ?
                """);
        List<Object> params = new ArrayList<>(List.of(context.tenantId(),
                java.sql.Date.valueOf(from), java.sql.Date.valueOf(to)));
        if (accountId != null) {
            sql.append(" and financial_account_id = ?");
            params.add(accountId);
        }
        if (minAmount != null) {
            sql.append(" and abs(amount) >= ?");
            params.add(minAmount);
        }
        sql.append(" order by booking_date limit ").append(MAX_TRANSACTIONS);

        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), params.toArray());
        if (rows.isEmpty()) {
            return AgentResult.skipped("Bankabgleich: keine unzugeordneten Buchungen im Zeitraum "
                    + from + " bis " + to + ".");
        }

        int unique = 0;
        int ambiguous = 0;
        int withoutCandidate = 0;
        int tasks = 0;
        List<AgentResult.Produced> produced = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            UUID transactionId = (UUID) row.get("id");
            CamtParser.Transaction transaction = toTransaction(row);
            List<PaymentMatchingService.Proposal> candidates = matching.proposeMatches(
                    context.tenantId(), transaction);
            if (candidates.isEmpty()) {
                withoutCandidate++;
                continue;
            }
            if (candidates.size() == 1) {
                unique++;
            }
            else {
                ambiguous++;
            }
            for (PaymentMatchingService.Proposal candidate : candidates) {
                jdbc.update("""
                        insert into payment_matches (id, tenant_id, invoice_id, bank_transaction_id,
                                                     match_type, status, matched_amount, currency,
                                                     confidence, match_reason)
                        values (gen_random_uuid(), ?, ?, ?, 'SUGGESTED', 'PROPOSED', ?,
                                coalesce((select currency from invoices where id = ?), 'EUR'), ?, ?)
                        on conflict do nothing
                        """, context.tenantId(), candidate.invoiceId(), transactionId,
                        candidate.amount(), candidate.invoiceId(),
                        BigDecimal.valueOf(candidate.confidence()),
                        "Bankabgleich: " + candidate.reason());
            }
            jdbc.update("update bank_transactions set status = 'SUGGESTED' "
                    + "where id = ? and tenant_id = ?", transactionId, context.tenantId());
            produced.add(new AgentResult.Produced("bank-reconciliation", transactionId.toString(),
                    "Buchung " + transaction.bookingDate() + " / " + transaction.signedAmount()
                            + (candidates.size() == 1 ? " — eindeutiger Vorschlag" : " — mehrdeutig")));

            if (tasks < MAX_TASKS) {
                String title = candidates.size() == 1
                        ? "Bankabgleich bestätigen: " + transaction.bookingDate() + " / "
                                + transaction.signedAmount()
                        : "Bankabgleich klären: " + transaction.bookingDate() + " / "
                                + transaction.signedAmount();
                AgentWorkspace.TaskRef task = workspace.ensureTask(context,
                        "banking-reconcile:" + transactionId, title,
                        "Vorschlag aus dem Bankabgleich. Bestätigung durch eine Person; der Agent "
                                + "bucht keine Zahlung.\n\n" + candidates.stream()
                                        .map(c -> "- Rechnung " + c.invoiceId() + ": " + c.reason())
                                        .collect(java.util.stream.Collectors.joining("\n")),
                        candidates.size() == 1 ? Task.Priority.NORMAL : Task.Priority.HIGH,
                        context.now().plus(java.time.Duration.ofDays(2)),
                        List.of("bank", "reconciliation"),
                        Map.of("bankTransactionId", transactionId.toString(),
                                "candidateCount", candidates.size(),
                                "toleranceCents", toleranceCents));
                if (task.created()) {
                    produced.add(new AgentResult.Produced("task", task.id().toString(), title));
                }
                tasks++;
            }
        }

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("scanned", String.valueOf(rows.size()));
        meta.put("uniqueCandidates", String.valueOf(unique));
        meta.put("ambiguous", String.valueOf(ambiguous));
        meta.put("withoutCandidate", String.valueOf(withoutCandidate));
        meta.put("toleranceCents", String.valueOf(toleranceCents));
        if (unique + ambiguous == 0) {
            return new AgentResult(false, "Bankabgleich: keine Zuordnung möglich — " + rows.size()
                    + " unzugeordnete Buchung(en), keine passt zu einer offenen Rechnung.",
                    produced, meta);
        }
        return AgentResult.done("Bankabgleich: " + unique + " eindeutige(r) und " + ambiguous
                + " mehrdeutige(r) Vorschlag/Vorschläge für " + rows.size()
                + " Buchung(en); keine Zahlung gebucht.", produced, meta);
    }

    private static CamtParser.Transaction toTransaction(Map<String, Object> row) {
        BigDecimal amount = row.get("amount") == null ? BigDecimal.ZERO
                : new BigDecimal(String.valueOf(row.get("amount")));
        return new CamtParser.Transaction(
                row.get("external_id") == null ? null : String.valueOf(row.get("external_id")),
                toLocalDate(row.get("booking_date")),
                toLocalDate(row.get("value_date")),
                amount.abs(),
                row.get("currency") == null ? "EUR" : String.valueOf(row.get("currency")),
                row.get("counterparty_name") == null ? null : String.valueOf(row.get("counterparty_name")),
                row.get("counterparty_iban") == null ? null : String.valueOf(row.get("counterparty_iban")),
                row.get("purpose") == null ? null : String.valueOf(row.get("purpose")),
                row.get("reference") == null ? null : String.valueOf(row.get("reference")),
                row.get("end_to_end_id") == null ? null : String.valueOf(row.get("end_to_end_id")),
                row.get("transaction_type") == null ? null : String.valueOf(row.get("transaction_type")),
                amount.signum() > 0);
    }

    private static LocalDate toLocalDate(Object value) {
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        if (value instanceof LocalDate date) {
            return date;
        }
        if (value instanceof java.time.LocalDateTime dateTime) {
            return dateTime.toLocalDate();
        }
        return value == null ? null : LocalDate.parse(String.valueOf(value));
    }
}
