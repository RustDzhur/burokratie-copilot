package de.buerokratiecopilot.agents.compliance;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.retention.RetentionService;

/**
 * {@code retention} — Aufbewahrung.
 *
 * <p>Calculates the retention and deletion deadline of a document from its retention rule — the
 * same rules the retention module applies — and lists the deletion candidates of the workspace.
 * <strong>The agent only reports.</strong> It never schedules or executes a deletion: an
 * unverified policy never counts as expired, a document under legal hold is reported as blocked,
 * and the actual deletion stays a separate, confirmed act of a person in the retention module.</p>
 */
@Component
public class RetentionAgent implements Agent {

    private static final int DEFAULT_UPCOMING_DAYS = 30;

    private final RetentionService retention;
    private final ComplianceQueries queries;

    public RetentionAgent(RetentionService retention, ComplianceQueries queries) {
        this.retention = retention;
        this.queries = queries;
    }

    @Override
    public String id() {
        return "retention";
    }

    @Override
    public AgentResult run(AgentContext context) {
        String documentIdRaw = context.input("documentId");
        if (documentIdRaw != null && !documentIdRaw.isBlank()) {
            UUID documentId = parseUuid(documentIdRaw);
            if (documentId == null) {
                return AgentResult.skipped("documentId ist keine gültige UUID");
            }
            return deadlineFor(context, documentId);
        }
        return sweep(context);
    }

    /** The deletion deadline of one document, computed from its retention rule. */
    private AgentResult deadlineFor(AgentContext context, UUID documentId) {
        Map<String, Object> facts = queries.documentRetentionFacts(context.tenantId(), documentId);
        if (facts == null) {
            return AgentResult.skipped("Dokument nicht gefunden");
        }
        if (facts.get("deleted_at") != null) {
            return AgentResult.skipped("Dokument ist bereits gelöscht — keine Frist nötig");
        }
        if (facts.get("retention_policy_id") == null) {
            return AgentResult.skipped(
                    "Keine Aufbewahrungsregel zugeordnet — die Plattform erfindet keine Aufbewahrungsfrist");
        }
        Object monthsValue = facts.get("retention_period_months");
        if (!(monthsValue instanceof Number months)) {
            return AgentResult.skipped(
                    "Die Aufbewahrungsregel legt keine Frist fest; es ist nichts zu berechnen");
        }

        String trigger = String.valueOf(facts.get("retention_trigger"));
        LocalDate anchor = switch (trigger) {
            case "DOCUMENT_DATE" -> asLocalDate(facts.get("document_date"));
            case "RECEIPT_DATE" -> asLocalDate(facts.get("received_at"));
            case "CASE_CLOSED" -> queries.latestCaseClosedDate(context.tenantId(), documentId);
            case "CONTRACT_END" -> queries.latestContractEndDate(context.tenantId(), documentId);
            default -> null;
        };
        if ("MANUAL".equals(trigger)) {
            return AgentResult.skipped(
                    "Die Aufbewahrungsregel nutzt eine manuelle Frist — es gibt nichts zu berechnen");
        }
        if (anchor == null) {
            return AgentResult.skipped("Das Ankerdatum für den Auslöser " + trigger
                    + " ist nicht vorhanden — die Frist kann nicht berechnet werden");
        }

        LocalDate deadline = anchor.plusMonths(months.longValue());
        LocalDate today = LocalDate.now();
        String status;
        if (Boolean.TRUE.equals(facts.get("legal_hold"))) {
            status = "BLOCKED";
        }
        else if (Boolean.TRUE.equals(facts.get("requires_verification"))) {
            status = "UNVERIFIED";
        }
        else if (deadline.isBefore(today)) {
            status = "EXPIRED";
        }
        else if (!deadline.isAfter(today.plusDays(upcomingDays(context)))) {
            status = "DUE_SOON";
        }
        else {
            status = "OK";
        }

        String label = (String) facts.get("original_filename") + ": Löschung ab " + deadline
                + " (Regel " + facts.get("policy_code") + ", " + months.longValue() + " Monate ab " + trigger + ")";
        context.addProduced("retentionDeadline", documentId.toString(), label);

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("documentId", documentId.toString());
        meta.put("policyCode", String.valueOf(facts.get("policy_code")));
        meta.put("trigger", trigger);
        meta.put("anchor", anchor.toString());
        meta.put("retentionPeriodMonths", String.valueOf(months.longValue()));
        meta.put("deadline", deadline.toString());
        meta.put("status", status);
        meta.put("policyVerified", String.valueOf(!Boolean.TRUE.equals(facts.get("requires_verification"))));
        meta.put("legalHold", String.valueOf(Boolean.TRUE.equals(facts.get("legal_hold"))));
        meta.put("deletionExecuted", "false");
        return AgentResult.done("Löschfrist für " + facts.get("original_filename") + ": " + deadline + " (" + status + ")",
                context.produced(), meta);
    }

    /** The deletion candidates of the whole workspace, plus what is due soon and what is blocked. */
    private AgentResult sweep(AgentContext context) {
        List<Map<String, Object>> due = retention.dueForDeletion(context.tenantId());
        List<Map<String, Object>> upcoming = queries.upcomingDeletions(context.tenantId(), upcomingDays(context));
        List<Map<String, Object>> holds = retention.underLegalHold(context.tenantId());

        for (Map<String, Object> candidate : due) {
            context.addProduced("deletionCandidate", String.valueOf(candidate.get("id")),
                    candidate.get("original_filename") + ": Frist abgelaufen (Regel "
                            + candidate.get("policy_code") + ")");
        }

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("deletionCandidates", String.valueOf(due.size()));
        meta.put("upcomingDays", String.valueOf(upcomingDays(context)));
        meta.put("upcoming", String.valueOf(upcoming.size()));
        meta.put("legalHolds", String.valueOf(holds.size()));
        meta.put("deletionExecuted", "false");

        String summary = due.isEmpty()
                ? "Aufbewahrungsprüfung: keine Löschkandidaten"
                : "Aufbewahrungsprüfung: " + due.size() + " Löschkandidaten — Löschung nur nach Bestätigung einer Person";
        if (!upcoming.isEmpty()) {
            summary += ", " + upcoming.size() + " demnächst fällig";
        }
        if (!holds.isEmpty()) {
            summary += ", " + holds.size() + " unter Legal Hold";
        }
        return AgentResult.done(summary, context.produced(), meta);
    }

    private int upcomingDays(AgentContext context) {
        String raw = context.input("upcomingDays");
        if (raw == null || raw.isBlank()) {
            return DEFAULT_UPCOMING_DAYS;
        }
        try {
            return Math.min(Math.max(Integer.parseInt(raw.strip()), 0), 3650);
        }
        catch (NumberFormatException ex) {
            return DEFAULT_UPCOMING_DAYS;
        }
    }

    private static LocalDate asLocalDate(Object value) {
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime().toLocalDate();
        }
        return null;
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw.strip());
        }
        catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
