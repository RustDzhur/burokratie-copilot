package de.buerokratiecopilot.agents.finance;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.banking.CamtParser;
import de.buerokratiecopilot.banking.PaymentMatchingService;

/**
 * {@code payment-match} — Zahlungsabgleich.
 *
 * <p>Scans unmatched bank credits and asks the banking module's own matching rules for candidates
 * (invoice number quoted by the bank, or exactly the open amount). Candidates are recorded as
 * {@code SUGGESTED}/{@code PROPOSED} matches and the transaction is marked {@code SUGGESTED} — never
 * matched automatically, because an ambiguous match is worse than an unreconciled one.</p>
 *
 * <p>Idempotency: the schema's unique pair index on payment matches swallows duplicates, and only
 * {@code UNMATCHED} transactions are picked up, so a repeated run proposes nothing twice.</p>
 */
@Component
public class PaymentMatchAgent implements Agent {

    private static final int MAX_TRANSACTIONS = 200;

    private final JdbcTemplate jdbc;
    private final PaymentMatchingService matching;

    public PaymentMatchAgent(JdbcTemplate jdbc, PaymentMatchingService matching) {
        this.jdbc = jdbc;
        this.matching = matching;
    }

    @Override
    public String id() {
        return "payment-match";
    }

    @Override
    public AgentResult run(AgentContext context) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, booking_date, value_date, amount, currency, counterparty_name,
                       counterparty_iban, purpose, reference, end_to_end_id, transaction_type,
                       external_id
                from bank_transactions
                where tenant_id = ? and status = 'UNMATCHED' and amount > 0
                order by booking_date limit ?
                """, context.tenantId(), MAX_TRANSACTIONS);
        if (rows.isEmpty()) {
            return AgentResult.skipped("Zahlungsabgleich: keine unzugeordneten Bankeingänge vorhanden.");
        }

        int proposals = 0;
        int withCandidates = 0;
        List<AgentResult.Produced> produced = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            UUID transactionId = (UUID) row.get("id");
            CamtParser.Transaction transaction = toTransaction(row);
            List<PaymentMatchingService.Proposal> candidates = matching.proposeMatches(
                    context.tenantId(), transaction);
            if (candidates.isEmpty()) {
                continue;
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
                        BigDecimal.valueOf(candidate.confidence()), candidate.reason());
                proposals++;
                produced.add(new AgentResult.Produced("payment-proposal", candidate.invoiceId().toString(),
                        "Bankeingang " + FinanceSupport.money(candidate.amount()) + ": "
                                + candidate.reason()));
            }
            jdbc.update("update bank_transactions set status = 'SUGGESTED' where id = ? and tenant_id = ?",
                    transactionId, context.tenantId());
            withCandidates++;
        }

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("scanned", String.valueOf(rows.size()));
        meta.put("withCandidates", String.valueOf(withCandidates));
        meta.put("proposals", String.valueOf(proposals));
        if (proposals == 0) {
            return new AgentResult(false,
                    "Zahlungsabgleich: keine Kandidaten — " + rows.size()
                            + " unzugeordnete(r) Bankeingang/Bankeingänge, keine passt zu einer offenen Rechnung.",
                    produced, meta);
        }
        return AgentResult.done(
                "Zahlungsabgleich: " + proposals + " Vorschlag/Vorschläge für " + withCandidates
                        + " Bankeingang/Bankeingänge markiert (keine automatische Zuordnung).",
                produced, meta);
    }

    private static CamtParser.Transaction toTransaction(Map<String, Object> row) {
        return new CamtParser.Transaction(
                row.get("external_id") == null ? null : String.valueOf(row.get("external_id")),
                FinanceSupport.toLocalDate(row.get("booking_date")),
                FinanceSupport.toLocalDate(row.get("value_date")),
                FinanceSupport.nvl(row.get("amount")),
                row.get("currency") == null ? "EUR" : String.valueOf(row.get("currency")),
                row.get("counterparty_name") == null ? null : String.valueOf(row.get("counterparty_name")),
                row.get("counterparty_iban") == null ? null : String.valueOf(row.get("counterparty_iban")),
                row.get("purpose") == null ? null : String.valueOf(row.get("purpose")),
                row.get("reference") == null ? null : String.valueOf(row.get("reference")),
                row.get("end_to_end_id") == null ? null : String.valueOf(row.get("end_to_end_id")),
                row.get("transaction_type") == null ? null : String.valueOf(row.get("transaction_type")),
                true);
    }
}
