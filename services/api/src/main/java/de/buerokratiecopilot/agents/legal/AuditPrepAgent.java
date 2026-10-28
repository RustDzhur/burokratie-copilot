package de.buerokratiecopilot.agents.legal;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code legal-audit-prep} — Audit-Vorbereitung.
 *
 * <p>Collects the evidence an audit asks for: policy documents, approvals, audit-trail entries, open
 * measures, pending reviews and documents under legal hold. Each item is a count over a defined
 * period, so the folder can be checked; a missing item is reported as a gap with an owner and turned
 * into a task.</p>
 *
 * <p>The agent does not judge whether the company is audit-ready — it shows what exists and what does
 * not. The distinction matters: "no approvals in the period" is a fact, "not audit-ready" would be an
 * opinion the agent cannot support.</p>
 */
@Component
public class AuditPrepAgent implements Agent {

    static final String ARTIFACT_KIND = "audit-folder";
    static final List<String> AUDIT_TYPES = List.of("INTERNAL", "EXTERNAL", "CERTIFICATION");

    private final JdbcTemplate jdbc;
    private final AgentTaskFactory tasks;
    private final AgentArtifactStore artifacts;

    public AuditPrepAgent(JdbcTemplate jdbc, AgentTaskFactory tasks, AgentArtifactStore artifacts) {
        this.jdbc = jdbc;
        this.tasks = tasks;
        this.artifacts = artifacts;
    }

    @Override
    public String id() {
        return "legal-audit-prep";
    }

    /** One checklist line: what was expected, what was found, and whether that is enough. */
    public record Evidence(String key, String label, long found, long expected, boolean present,
                           boolean informational, String note) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("key", key);
            map.put("label", label);
            map.put("found", found);
            map.put("expected", expected);
            map.put("present", present);
            map.put("informational", informational);
            map.put("note", note);
            return map;
        }
    }

    @Override
    public AgentResult run(AgentContext context) {
        String fromRaw = context.input("periodFrom");
        String toRaw = context.input("periodTo");
        if (AgentInputs.text(fromRaw) == null || AgentInputs.text(toRaw) == null) {
            return AgentResult.refused("NO_INPUT",
                    "Für die Audit-Vorbereitung werden periodFrom und periodTo benötigt.");
        }
        LocalDate from = AgentInputs.date(fromRaw);
        LocalDate to = AgentInputs.date(toRaw);
        if (from == null || to == null) {
            return AgentResult.refused("INVALID_PERIOD",
                    "Der Audit-Zeitraum ist ungültig: " + fromRaw + " bis " + toRaw);
        }
        if (to.isBefore(from)) {
            return AgentResult.refused("INVALID_PERIOD",
                    "Das Ende des Audit-Zeitraums liegt vor dem Beginn.");
        }

        String typeRaw = AgentInputs.text(context.input("auditType"));
        String auditType = typeRaw == null ? "INTERNAL" : typeRaw.toUpperCase(Locale.ROOT);
        if (!AUDIT_TYPES.contains(auditType)) {
            return AgentResult.refused("INVALID_INPUT",
                    "Unbekannter Audit-Typ: " + typeRaw + " (erlaubt: "
                            + String.join(", ", AUDIT_TYPES) + ")");
        }
        String scope = AgentInputs.text(context.input("scope"));
        String auditor = AgentInputs.text(context.input("auditorName"));

        Map<String, Long> counts = counts(context.tenantId(), from, to);
        List<Evidence> checklist = checklist(counts);
        List<Evidence> gaps = checklist.stream()
                .filter(item -> !item.present() && !item.informational()).toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", ARTIFACT_KIND);
        payload.put("auditType", auditType);
        payload.put("periodFrom", from.toString());
        payload.put("periodTo", to.toString());
        payload.put("scope", scope);
        payload.put("auditor", auditor);
        payload.put("checklist", checklist.stream().map(Evidence::asMap).toList());
        payload.put("gaps", gaps.stream().map(item -> {
            Map<String, Object> gap = new LinkedHashMap<>();
            gap.put("key", item.key());
            gap.put("label", item.label());
            gap.put("message", item.note());
            gap.put("responsibleUserId", context.actorUserId() == null ? null
                    : context.actorUserId().toString());
            return gap;
        }).toList());
        payload.put("note", "Der Agent bewertet nicht die Auditfähigkeit des Unternehmens.");

        String sourceKey = AgentInputs.sourceKey(ARTIFACT_KIND, auditType, from.toString(),
                to.toString(), AgentInputs.slug(scope, 40));
        AgentArtifactStore.Artifact artifact = artifacts.put(context.tenantId(),
                context.actorUserId(), ARTIFACT_KIND, sourceKey, payload);
        context.addProduced("audit-folder", artifact.id().toString(),
                "Audit-Ordner " + auditType + " " + from + " – " + to + " ("
                        + checklist.size() + " Nachweise, " + gaps.size() + " Lücken)");

        int created = 0;
        for (Evidence gap : gaps) {
            String title = switch (gap.key()) {
                case "OPEN_MEASURES" -> "Offene Maßnahmen für das Audit abschließen";
                case "PENDING_REVIEWS" -> "Dokumentenprüfungen für das Audit abschließen";
                default -> "Audit-Nachweis fehlt: " + gap.label();
            };
            String description = "Audit " + auditType + " " + from + " bis " + to
                    + (scope == null ? "" : " (" + scope + ")") + ": " + gap.note()
                    + (auditor == null ? "" : " Auditor: " + auditor);
            LocalDate today = LocalDate.ofInstant(context.now(), AgentInputs.BERLIN);
            AgentTaskFactory.Request request = new AgentTaskFactory.Request(title, description,
                    "Der Nachweis liegt im Audit-Ordner oder die Lücke ist begründet dokumentiert.",
                    AgentInputs.dueAt(today, 14), Task.Priority.HIGH,
                    List.of("audit", auditType.toLowerCase(Locale.ROOT)), null,
                    context.actorUserId());
            var optionalTask = tasks.createIfAbsent(context, request);
            if (optionalTask.isPresent()) {
                AgentTaskFactory.produce(context, optionalTask.get());
                created++;
            }
        }

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("auditType", auditType);
        meta.put("periodFrom", from.toString());
        meta.put("periodTo", to.toString());
        meta.put("checklistItems", String.valueOf(checklist.size()));
        meta.put("gaps", String.valueOf(gaps.size()));
        meta.put("tasksCreated", String.valueOf(created));
        meta.put("artifactId", artifact.id().toString());
        meta.put("artifactReused", String.valueOf(artifact.reused()));
        meta.put("sourceKey", sourceKey);
        return AgentResult.done("Audit-Vorbereitung (" + auditType + ") " + from + " – " + to + ": "
                + (checklist.size() - gaps.size()) + " von " + checklist.size()
                + " Nachweisen vorhanden, " + gaps.size() + " Lücke(n), " + created
                + " Aufgabe(n).", context.produced(), meta);
    }

    private Map<String, Long> counts(UUID tenantId, LocalDate from, LocalDate to) {
        java.sql.Timestamp start = java.sql.Timestamp.valueOf(from.atStartOfDay());
        java.sql.Timestamp end = java.sql.Timestamp.valueOf(to.plusDays(1).atStartOfDay());
        Map<String, Object> row = jdbc.queryForMap("""
                select
                  (select count(*) from documents d
                    left join document_types dt on dt.id = d.document_type_id
                    where d.tenant_id = ? and d.deleted_at is null
                      and d.received_at >= ? and d.received_at < ?
                      and (dt.category in ('CONTRACTS', 'LEGAL')
                           or dt.code in ('agb', 'vertrag', 'richtlinie'))) as policies,
                  (select count(*) from approvals
                    where tenant_id = ? and created_at >= ? and created_at < ?) as approvals,
                  (select count(*) from audit_events
                    where tenant_id = ? and created_at >= ? and created_at < ?) as audit_events,
                  (select count(*) from tasks
                    where tenant_id = ? and deleted_at is null
                      and status not in ('DONE', 'CANCELLED')) as open_tasks,
                  (select count(*) from documents
                    where tenant_id = ? and deleted_at is null
                      and review_status in ('PENDING', 'IN_REVIEW')) as pending_reviews,
                  (select count(*) from documents
                    where tenant_id = ? and deleted_at is null and legal_hold) as legal_hold
                """, tenantId, start, end, tenantId, start, end, tenantId, start, end, tenantId,
                tenantId, tenantId);
        return Map.of(
                "policies", number(row.get("policies")),
                "approvals", number(row.get("approvals")),
                "audit_events", number(row.get("audit_events")),
                "open_tasks", number(row.get("open_tasks")),
                "pending_reviews", number(row.get("pending_reviews")),
                "legal_hold", number(row.get("legal_hold")));
    }

    /**
     * The audit checklist as a pure function over the counts. Visible for tests: the judgement
     * "enough evidence" lives here and nowhere else.
     */
    public static List<Evidence> checklist(Map<String, Long> counts) {
        long policies = counts.getOrDefault("policies", 0L);
        long approvals = counts.getOrDefault("approvals", 0L);
        long auditEvents = counts.getOrDefault("audit_events", 0L);
        long openTasks = counts.getOrDefault("open_tasks", 0L);
        long pendingReviews = counts.getOrDefault("pending_reviews", 0L);
        long legalHold = counts.getOrDefault("legal_hold", 0L);

        List<Evidence> items = new ArrayList<>();
        items.add(new Evidence("POLICIES", "Richtlinien und Regelungen", policies, 1, policies > 0,
                false, policies > 0
                        ? policies + " Richtlinien-/Regelungsdokument(e) im Zeitraum"
                        : "Kein Richtlinien- oder Regelungsdokument im Zeitraum abgelegt"));
        items.add(new Evidence("APPROVALS", "Freigaben", approvals, 1, approvals > 0, false,
                approvals > 0 ? approvals + " Freigabe(n) im Zeitraum"
                        : "Keine Freigabe im Zeitraum dokumentiert"));
        items.add(new Evidence("AUDIT_LOG", "Prüfprotokolle", auditEvents, 1, auditEvents > 0, false,
                auditEvents > 0 ? auditEvents + " Audit-Ereignis(se) im Zeitraum"
                        : "Keine Audit-Ereignisse im Zeitraum protokolliert"));
        items.add(new Evidence("OPEN_MEASURES", "Offene Maßnahmen", openTasks, 0, openTasks == 0,
                false, openTasks == 0 ? "Keine offenen Aufgaben"
                        : openTasks + " offene Aufgabe(n) im Konto"));
        items.add(new Evidence("PENDING_REVIEWS", "Dokumentenprüfungen", pendingReviews, 0,
                pendingReviews == 0, false, pendingReviews == 0 ? "Keine offene Dokumentenprüfung"
                        : pendingReviews + " Dokument(en) warten auf Prüfung"));
        items.add(new Evidence("LEGAL_HOLD", "Dokumente unter Legal Hold", legalHold, 0, true, true,
                legalHold == 0 ? "Kein Dokument unter Legal Hold"
                        : legalHold + " Dokument(en) unter Legal Hold — im Audit-Ordner nennen"));
        return List.copyOf(items);
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
