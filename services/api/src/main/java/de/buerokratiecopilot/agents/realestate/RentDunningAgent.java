package de.buerokratiecopilot.agents.realestate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentArtifactStore;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentInputs;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentTaskFactory;
import de.buerokratiecopilot.mail.outbound.OutboundMessageService;
import de.buerokratiecopilot.mail.outbound.OutboundMessageService.DraftRequest;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code realestate-rent-dunning} — Mietrückstände.
 *
 * <p>Finds open rent invoices of {@code RENT} contracts inside the requested period and prepares the
 * next dunning stage per contract: one draft letter, one task for the responsible person, and a
 * record of what is overdue. The stage is derived from the days past due, so the letter does not
 * claim a stage the data does not support.</p>
 *
 * <p>Nothing is sent and no legal step is initiated. A contract without an e-mail address yields a
 * task and a gap, not a fictional letter recipient. The property filter reads
 * {@code contracts.metadata->>'propertyId'}, because this platform has no property table; when no
 * contract carries the reference, the run says so instead of guessing.</p>
 */
@Component
public class RentDunningAgent implements Agent {

    static final String ARTIFACT_KIND = "rent-dunning";
    static final int DEFAULT_LEVELS = 3;
    static final int DEFAULT_GRACE_DAYS = 5;
    static final int DAYS_PER_STAGE = 14;
    private static final int MAX_CONTRACTS = 200;

    private final JdbcTemplate jdbc;
    private final OutboundMessageService outbound;
    private final AgentTaskFactory tasks;
    private final AgentArtifactStore artifacts;

    public RentDunningAgent(JdbcTemplate jdbc, OutboundMessageService outbound,
                            AgentTaskFactory tasks, AgentArtifactStore artifacts) {
        this.jdbc = jdbc;
        this.outbound = outbound;
        this.tasks = tasks;
        this.artifacts = artifacts;
    }

    @Override
    public String id() {
        return "realestate-rent-dunning";
    }

    @Override
    public AgentResult run(AgentContext context) {
        String fromRaw = context.input("periodFrom");
        String toRaw = context.input("periodTo");
        if (AgentInputs.text(fromRaw) == null || AgentInputs.text(toRaw) == null) {
            return AgentResult.refused("NO_INPUT",
                    "Für die Mietrückstände werden periodFrom und periodTo benötigt.");
        }
        LocalDate from = AgentInputs.date(fromRaw);
        LocalDate to = AgentInputs.date(toRaw);
        if (from == null || to == null) {
            return AgentResult.refused("INVALID_PERIOD",
                    "Der Zeitraum ist ungültig: " + fromRaw + " bis " + toRaw);
        }
        if (to.isBefore(from)) {
            return AgentResult.refused("INVALID_PERIOD",
                    "Das Ende des Zeitraums liegt vor dem Beginn.");
        }
        int levels = AgentInputs.integer(context.input("dunningLevels"), DEFAULT_LEVELS, 1, 5);
        int graceDays = AgentInputs.integer(context.input("graceDays"), DEFAULT_GRACE_DAYS, 0, 90);
        String propertyId = AgentInputs.text(context.input("propertyId"));
        LocalDate today = LocalDate.ofInstant(context.now(), AgentInputs.BERLIN);
        LocalDate cutoff = today.minusDays(graceDays);

        List<Map<String, Object>> rows = arrears(context.tenantId(), from, to, cutoff, propertyId);
        if (rows.isEmpty()) {
            return AgentResult.refused("NO_ARREARS",
                    "Keine offenen Mietzahlungen im Zeitraum " + from + " bis " + to
                            + (propertyId == null ? "" : " für das Objekt " + propertyId) + ".",
                    Map.of("periodFrom", from.toString(), "periodTo", to.toString()));
        }

        Map<String, List<Map<String, Object>>> byContract = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String key = String.valueOf(row.get("contract_id"));
            byContract.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }

        List<Map<String, Object>> contracts = new ArrayList<>();
        List<Map<String, Object>> gaps = new ArrayList<>();
        int drafts = 0;
        int createdTasks = 0;
        BigDecimal totalOpen = BigDecimal.ZERO;
        int contractCount = 0;
        for (List<Map<String, Object>> invoices : byContract.values()) {
            if (contractCount >= MAX_CONTRACTS) {
                break;
            }
            contractCount++;
            Map<String, Object> first = invoices.getFirst();
            String contractId = String.valueOf(first.get("contract_id"));
            String contractTitle = String.valueOf(first.get("contract_title"));
            String contractNumber = first.get("contract_number") == null ? null
                    : String.valueOf(first.get("contract_number"));
            LocalDate maxDue = null;
            BigDecimal open = BigDecimal.ZERO;
            List<Map<String, Object>> invoiceRows = new ArrayList<>();
            for (Map<String, Object> invoice : invoices) {
                LocalDate due = AgentInputs.toLocalDate(invoice.get("due_date"));
                if (due != null && (maxDue == null || due.isBefore(maxDue))) {
                    maxDue = due;
                }
                BigDecimal amount = decimal(invoice.get("gross_amount"))
                        .subtract(decimal(invoice.get("paid_amount")));
                open = open.add(amount);
                Map<String, Object> invoiceRow = new LinkedHashMap<>();
                invoiceRow.put("invoiceId", String.valueOf(invoice.get("id")));
                invoiceRow.put("invoiceNumber", invoice.get("invoice_number"));
                invoiceRow.put("dueDate", due == null ? null : due.toString());
                invoiceRow.put("openAmount", amount.toString());
                invoiceRows.add(invoiceRow);
            }
            long daysOverdue = maxDue == null ? 0
                    : ChronoUnit.DAYS.between(maxDue, today);
            int level = levelFor(daysOverdue, graceDays, levels);
            if (level < 1) {
                continue;
            }
            totalOpen = totalOpen.add(open);
            String email = invoices.stream().map(invoice -> invoice.get("email"))
                    .filter(value -> value != null && !String.valueOf(value).isBlank())
                    .map(String::valueOf).findFirst().orElse(null);
            String contactName = first.get("contact_name") == null ? null
                    : String.valueOf(first.get("contact_name"));
            String idempotencyKey = "rent-dunning-v1:" + contractId + ":L" + level;

            String draftRef = null;
            if (email != null) {
                List<UUID> existing = jdbc.query("""
                        select id from outbound_messages
                        where tenant_id = ? and idempotency_key = ? and deleted_at is null
                        """, (rs, rowNum) -> rs.getObject("id", UUID.class), context.tenantId(),
                        idempotencyKey);
                if (existing.isEmpty()) {
                    draftRef = outbound.draft(new DraftRequest(context.tenantId(), null, "EMAIL",
                            "Mietrückstand – " + contractTitle + " (Mahnstufe " + level + ")",
                            letterBody(contractTitle, contractNumber, invoiceRows, open, level),
                            null, null, List.of(email), List.of(), null, idempotencyKey,
                            context.actorUserId())).toString();
                    drafts++;
                    context.addProduced("rent-dunning-draft", draftRef,
                            "Mahnstufe " + level + " an " + (contactName == null ? email
                                    : contactName) + " (" + invoiceRows.size() + " Rechnung(en))");
                }
            }
            else {
                gaps.add(gap("NO_EMAIL", "Für den Vertrag " + contractTitle
                        + " ist keine E-Mail-Adresse hinterlegt; es wurde kein Entwurf erstellt."));
            }

            String taskTitle = "Mietrückstand " + (contractNumber == null ? contractTitle
                    : contractNumber + " – " + contractTitle) + ": Mahnstufe " + level;
            String description = "Offen: " + AgentInputs.money(open) + " aus " + invoiceRows.size()
                    + " Rechnung(en), älteste Fälligkeit " + (maxDue == null ? "unbekannt"
                            : maxDue + " (" + daysOverdue + " Tage überfällig)")
                    + ". Mahnstufe " + level + " von " + levels + ". "
                    + (draftRef == null ? "Kein E-Mail-Entwurf (Adresse fehlt)."
                            : "Entwurf liegt im Postausgang und ist NICHT versendet.")
                    + " Rechtliche Schritte werden nicht eingeleitet.";
            AgentTaskFactory.Request request = new AgentTaskFactory.Request(taskTitle, description,
                    "Die Zahlung ist eingegangen oder die nächste Mahnstufe ist freigegeben.",
                    AgentInputs.dueAt(today, 3), Task.Priority.HIGH, List.of("miete", "mahnung"),
                    null, context.actorUserId());
            var optionalTask = tasks.createIfAbsent(context, request);
            if (optionalTask.isPresent()) {
                AgentTaskFactory.produce(context, optionalTask.get());
                createdTasks++;
            }

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("contractId", contractId);
            entry.put("contractTitle", contractTitle);
            entry.put("contractNumber", contractNumber);
            entry.put("contactName", contactName);
            entry.put("emailPresent", email != null);
            entry.put("level", level);
            entry.put("levels", levels);
            entry.put("daysOverdue", daysOverdue);
            entry.put("openAmount", open.toString());
            entry.put("invoices", invoiceRows);
            entry.put("draftId", draftRef);
            contracts.add(entry);
        }

        if (contracts.isEmpty()) {
            return AgentResult.refused("NO_ARREARS",
                    "Innerhalb der Karenzzeit von " + graceDays + " Tagen gibt es keine offene "
                            + "Mietzahlung im Zeitraum.", Map.of("graceDays",
                            String.valueOf(graceDays)));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", ARTIFACT_KIND);
        payload.put("periodFrom", from.toString());
        payload.put("periodTo", to.toString());
        payload.put("propertyId", propertyId);
        payload.put("graceDays", graceDays);
        payload.put("levels", levels);
        payload.put("totalOpen", totalOpen.toString());
        payload.put("contracts", contracts);
        payload.put("gaps", gaps);
        payload.put("note", "Kein Versand; der Agent leitet keine rechtlichen Schritte ein.");

        String sourceKey = AgentInputs.sourceKey(ARTIFACT_KIND, from.toString(), to.toString(),
                AgentInputs.slug(propertyId, 40), "L" + levels, "G" + graceDays);
        AgentArtifactStore.Artifact artifact = artifacts.put(context.tenantId(),
                context.actorUserId(), ARTIFACT_KIND, sourceKey, payload);
        context.addProduced("rent-dunning", artifact.id().toString(),
                "Mietrückstände " + from + " – " + to + ": " + contracts.size() + " Vertrag/"
                        + "Verträge, offen " + AgentInputs.money(totalOpen));

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("contracts", String.valueOf(contracts.size()));
        meta.put("invoices", String.valueOf(rows.size()));
        meta.put("drafts", String.valueOf(drafts));
        meta.put("tasksCreated", String.valueOf(createdTasks));
        meta.put("noEmail", String.valueOf(gaps.size()));
        meta.put("sendsNothing", "true");
        meta.put("requiresApproval", "true");
        meta.put("artifactId", artifact.id().toString());
        meta.put("artifactReused", String.valueOf(artifact.reused()));
        meta.put("sourceKey", sourceKey);
        return AgentResult.done("Mietrückstände " + from + " – " + to + ": " + contracts.size()
                + " Vertrag/Verträge, offen " + AgentInputs.money(totalOpen) + ", " + drafts
                + " Entwurf/Entwürfe vorbereitet (kein Versand), " + createdTasks
                + " Aufgabe(n). Rechtliche Schritte werden nicht eingeleitet.", context.produced(),
                meta);
    }

    /**
     * Stage from the days past due: the first stage starts after the grace period, each further
     * stage every {@value #DAYS_PER_STAGE} days, capped at the configured number. Visible for tests.
     */
    public static int levelFor(long daysOverdue, int graceDays, int levels) {
        if (levels < 1 || daysOverdue < graceDays) {
            return 0;
        }
        int stage = 1 + (int) ((daysOverdue - graceDays) / DAYS_PER_STAGE);
        return Math.min(stage, levels);
    }

    private List<Map<String, Object>> arrears(UUID tenantId, LocalDate from, LocalDate to,
                                              LocalDate cutoff, String propertyId) {
        StringBuilder sql = new StringBuilder("""
                select i.id, i.invoice_number, i.gross_amount, i.paid_amount, i.due_date,
                       i.contract_id, c.title as contract_title, c.contract_number,
                       ct.display_name as contact_name, ct.email::text as email
                from invoices i
                join contracts c on c.id = i.contract_id and c.tenant_id = i.tenant_id
                left join contacts ct on ct.id = c.counterparty_contact_id
                where i.tenant_id = ? and i.deleted_at is null and c.deleted_at is null
                  and i.direction = 'OUTGOING' and c.contract_type = 'RENT'
                  and i.status in ('APPROVED', 'SCHEDULED', 'PARTIALLY_PAID', 'OVERDUE')
                  and i.payment_status in ('UNPAID', 'PARTIALLY_PAID')
                  and coalesce(i.gross_amount, 0) - coalesce(i.paid_amount, 0) > 0
                  and i.due_date between ? and ? and i.due_date < ?
                """);
        List<Object> parameters = new ArrayList<>();
        parameters.add(tenantId);
        parameters.add(java.sql.Date.valueOf(from));
        parameters.add(java.sql.Date.valueOf(to));
        parameters.add(java.sql.Date.valueOf(cutoff));
        if (propertyId != null) {
            sql.append(" and c.metadata ->> 'propertyId' = ?\n");
            parameters.add(propertyId);
        }
        sql.append(" order by i.due_date");
        return jdbc.queryForList(sql.toString(), parameters.toArray());
    }

    private static String letterBody(String contractTitle, String contractNumber,
                                     List<Map<String, Object>> invoices, BigDecimal open, int level) {
        StringBuilder body = new StringBuilder();
        body.append("Sehr geehrte Damen und Herren,\n\n");
        body.append("nach unseren Unterlagen sind für ").append(contractTitle)
                .append(contractNumber == null ? "" : " (" + contractNumber + ")")
                .append(" folgende Mietzahlungen offen:\n\n");
        for (Map<String, Object> invoice : invoices) {
            body.append("- ").append(invoice.get("invoiceNumber")).append(", fällig am ")
                    .append(invoice.get("dueDate")).append(", offen ")
                    .append(AgentInputs.money(new BigDecimal(String.valueOf(
                            invoice.get("openAmount")))))
                    .append("\n");
        }
        body.append("\nGesamt offen: ").append(AgentInputs.money(open)).append(".\n");
        body.append(level > 1 ? "Dies ist eine erneute Zahlungserinnerung. " : "");
        body.append("Bitte überweisen Sie den Betrag innerhalb von 14 Tagen auf das bekannte "
                + "Konto.\n\nMit freundlichen Grüßen");
        return body.toString();
    }

    private static BigDecimal decimal(Object raw) {
        if (raw == null) {
            return BigDecimal.ZERO;
        }
        if (raw instanceof BigDecimal value) {
            return value;
        }
        return new BigDecimal(String.valueOf(raw));
    }

    private static Map<String, Object> gap(String code, String message) {
        Map<String, Object> gap = new LinkedHashMap<>();
        gap.put("code", code);
        gap.put("message", message);
        return gap;
    }
}
