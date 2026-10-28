package de.buerokratiecopilot.agents.crm;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.notifications.NotificationRepository;
import de.buerokratiecopilot.notifications.NotificationService;
import de.buerokratiecopilot.notifications.domain.Notification;
import de.buerokratiecopilot.tasks.TaskRepository;
import de.buerokratiecopilot.tasks.TaskService;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code crm-followup} — Follow-up.
 *
 * <p>Plans the next touch with a contact by rules: {@code lead_new} (first touch after lead
 * capture), {@code after_activity} (interval after the last protocolled activity),
 * {@code stage_based} (interval by deal stage), or a default interval. Each plan becomes a task
 * in the tasks module (source {@code SYSTEM}, so it shows up in the manual distribution queue
 * until someone is assigned) and a notification for the assignee. Idempotent on a follow-up key
 * stored in the task metadata and on the notification dedup key, so a repeated run plans the
 * same touch exactly once.</p>
 */
@Component
public class CrmFollowupAgent implements Agent {

    private static final Map<String, Integer> RULE_INTERVALS = Map.of(
            "default", 7, "lead_new", 2, "after_activity", 7);
    private static final Map<String, Integer> STAGE_INTERVALS = Map.of(
            "NEW", 2, "CONTACTED", 3, "QUALIFIED", 5, "PROPOSAL", 7, "NEGOTIATION", 3);

    private final CrmStore store;
    private final TaskService taskService;
    private final TaskRepository taskRepository;
    private final NotificationService notificationService;
    private final NotificationRepository notificationRepository;

    public CrmFollowupAgent(CrmStore store, TaskService taskService, TaskRepository taskRepository,
                            NotificationService notificationService,
                            NotificationRepository notificationRepository) {
        this.store = store;
        this.taskService = taskService;
        this.taskRepository = taskRepository;
        this.notificationService = notificationService;
        this.notificationRepository = notificationRepository;
    }

    @Override
    public String id() {
        return "crm-followup";
    }

    @Override
    public AgentResult run(AgentContext context) {
        UUID tenantId = context.tenantId();
        UUID actor = context.actorUserId();
        Instant now = context.now();

        UUID caseId;
        UUID contactId;
        UUID assignee;
        try {
            caseId = parseUuid(context.input("caseId"));
            contactId = parseUuid(context.input("contactId"));
            assignee = parseUuid(context.input("assigneeUserId"));
        }
        catch (IllegalArgumentException e) {
            return AgentResult.failed("Follow-up: ungültige UUID: " + e.getMessage());
        }
        if (caseId == null && contactId == null) {
            return AgentResult.failed("Follow-up: 'caseId' oder 'contactId' ist erforderlich.");
        }
        String rule = context.input("rule") == null || context.input("rule").isBlank()
                ? "default" : context.input("rule").strip().toLowerCase(Locale.ROOT);
        if (!RULE_INTERVALS.containsKey(rule) && !"stage_based".equals(rule)) {
            return AgentResult.failed("Follow-up: unbekannte Regel '" + rule + "' — erwartet: "
                    + String.join(", ", RULE_INTERVALS.keySet()) + ", stage_based.");
        }
        Integer intervalDays = parsePositiveInt(context.input("intervalDays"));
        if (context.input("intervalDays") != null && intervalDays == null) {
            return AgentResult.failed("Follow-up: 'intervalDays' muss eine positive ganze Zahl sein.");
        }
        Instant explicitDue = parseInstant(context.input("dueAt"));
        if (context.input("dueAt") != null && explicitDue == null) {
            return AgentResult.failed("Follow-up: ungültiger Zeitpunkt 'dueAt' — ISO-8601 erwartet.");
        }
        String note = clean(context.input("note"));

        Instant due;
        String rationale;
        switch (rule) {
            case "lead_new" -> {
                if (contactId == null) {
                    return AgentResult.skipped("Follow-up: Regel 'lead_new' braucht eine 'contactId'.");
                }
                Optional<Map<String, Object>> row = store.findContactRow(tenantId, contactId);
                if (row.isEmpty()) {
                    return AgentResult.skipped("Follow-up: Kontakt " + contactId + " nicht gefunden.");
                }
                String stage = leadStageOf(row.get());
                if (!"NEW".equals(stage)) {
                    return AgentResult.skipped("Follow-up: Lead ist nicht in Stufe NEW (aktuell: "
                            + stage + "), kein Erst-Follow-up.");
                }
                int days = intervalDays != null ? intervalDays : RULE_INTERVALS.get(rule);
                due = now.plus(Duration.ofDays(days));
                rationale = "Regel lead_new: Erstkontakt nach " + days + " Tagen";
            }
            case "after_activity" -> {
                if (caseId == null) {
                    return AgentResult.skipped("Follow-up: Regel 'after_activity' braucht eine 'caseId'.");
                }
                Optional<Instant> last = store.lastActivityAt(tenantId, caseId, contactId);
                if (last.isEmpty()) {
                    return AgentResult.skipped("Follow-up: keine protokollierte Aktivität am Vorgang "
                            + caseId + ", nichts worauf das Follow-up aufsetzen kann.");
                }
                int days = intervalDays != null ? intervalDays : RULE_INTERVALS.get(rule);
                due = last.get().plus(Duration.ofDays(days));
                rationale = "Regel after_activity: letzte Aktivität + " + days + " Tage";
            }
            case "stage_based" -> {
                if (caseId == null) {
                    return AgentResult.skipped("Follow-up: Regel 'stage_based' braucht eine 'caseId'.");
                }
                Optional<CrmStore.DealInfo> info = store.dealInfo(tenantId, caseId);
                if (info.isEmpty() || info.get().stage() == null) {
                    return AgentResult.skipped("Follow-up: Vorgang " + caseId
                            + " trägt keine Deal-Stufe.");
                }
                String stage = info.get().stage();
                if ("WON".equals(stage) || "LOST".equals(stage)) {
                    return AgentResult.skipped("Follow-up: Deal ist beendet (" + stage + ").");
                }
                Integer stageDays = STAGE_INTERVALS.get(stage);
                if (stageDays == null) {
                    return AgentResult.skipped("Follow-up: für Stufe '" + stage
                            + "' ist kein Intervall hinterlegt.");
                }
                int days = intervalDays != null ? intervalDays : stageDays;
                Instant base = parseInstant(info.get().lastMovedAt());
                due = (base == null ? now : base).plus(Duration.ofDays(days));
                rationale = "Regel stage_based: Stufe " + stage + " → " + days + " Tage";
            }
            default -> {
                int days = intervalDays != null ? intervalDays : RULE_INTERVALS.get("default");
                due = now.plus(Duration.ofDays(days));
                rationale = "Regel default: " + days + " Tage";
            }
        }
        if (explicitDue != null) {
            due = explicitDue;
            rationale = rationale + "; expliziter Termin aus Input";
        }
        if (due.isBefore(now)) {
            return AgentResult.skipped("Follow-up: geplanter Zeitpunkt " + due
                    + " liegt in der Vergangenheit, nichts zu planen.");
        }

        String followupKey = "crm-followup:" + rule + ":"
                + (caseId != null ? caseId : contactId);

        // Idempotenz: dieselbe offene Aufgabe wird nicht doppelt angelegt.
        Optional<UUID> existing = store.findOpenFollowupTask(tenantId, followupKey);
        if (existing.isPresent()) {
            context.addProduced("task", existing.get().toString(), "Bestehendes Follow-up");
            return AgentResult.done("Follow-up existiert bereits (Aufgabe " + existing.get() + ").",
                    context.produced(), Map.of("taskId", existing.get().toString(),
                            "existing", "true", "dueAt", due.toString(), "rule", rule));
        }

        String title = "Follow-up: " + (caseId != null ? "Vorgang " + caseId
                : "Kontakt " + contactId);
        String description = (note == null ? "" : note + "\n") + rationale + " (Agent crm-followup)";
        Task task;
        try {
            task = taskService.create(new TaskService.CreateRequest(tenantId, caseId, null, title,
                            description, Task.Priority.NORMAL, "RULE", "crm-followup " + rule, due,
                            due.minus(Duration.ofDays(1)),
                            "Kontakt wurde erreicht oder der Termin ist dokumentiert.", false, null,
                            assignee, List.of("crm", "followup"), Task.Source.SYSTEM, null),
                    actor);
        }
        catch (RuntimeException e) {
            return AgentResult.failed("Follow-up: Aufgabe konnte nicht angelegt werden: "
                    + e.getMessage());
        }

        String metaNote = null;
        try {
            Task fresh = taskRepository.findById(task.getId()).orElse(null);
            if (fresh != null) {
                Map<String, Object> metadata = new HashMap<>(fresh.getMetadata());
                metadata.put("agent", "crm-followup");
                metadata.put("followupKey", followupKey);
                metadata.put("rule", rule);
                if (caseId != null) {
                    metadata.put("caseId", caseId.toString());
                }
                if (contactId != null) {
                    metadata.put("contactId", contactId.toString());
                }
                fresh.setMetadata(metadata);
                taskRepository.save(fresh);
            }
        }
        catch (RuntimeException e) {
            // Die Aufgabe existiert; nur die Idempotenz-Markierung fehlt dann. Ehrlich vermerken.
            metaNote = "Aufgaben-Metadaten konnten nicht ergänzt werden: " + e.getMessage();
        }

        context.addProduced("task", task.getId().toString(), title);

        boolean notified = false;
        UUID recipient = assignee != null ? assignee : actor;
        if (recipient != null) {
            String dedupKey = "followup:" + followupKey;
            boolean already = notificationRepository
                    .findByUserIdAndDedupKey(recipient, dedupKey).isPresent();
            if (!already) {
                try {
                    notificationService.notify(new NotificationService.Request(tenantId, recipient,
                            null, "FOLLOWUP_DUE", Notification.Category.TASK,
                            Notification.Severity.INFO, "Follow-up fällig", title,
                            Map.of("dueAt", due.toString(), "rule", rule),
                            "task", task.getId(), null, false, dedupKey));
                    notified = true;
                }
                catch (RuntimeException e) {
                    metaNote = (metaNote == null ? "" : metaNote + "; ")
                            + "Benachrichtigung fehlgeschlagen: " + e.getMessage();
                }
            }
        }

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("taskId", task.getId().toString());
        meta.put("dueAt", due.toString());
        meta.put("rule", rule);
        meta.put("created", "true");
        meta.put("notified", String.valueOf(notified));
        if (caseId != null) {
            meta.put("caseId", caseId.toString());
        }
        if (contactId != null) {
            meta.put("contactId", contactId.toString());
        }
        if (metaNote != null) {
            meta.put("notes", metaNote);
        }
        return AgentResult.done("Follow-up geplant: " + title + " (fällig " + due + ").",
                context.produced(), meta);
    }

    /** The lead stage from the contact metadata; a lead without a stage counts as NEW. */
    private static String leadStageOf(Map<String, Object> row) {
        Object metadata = row.get("metadata");
        if (!(metadata instanceof Map<?, ?> metaMap)) {
            return null;
        }
        Object lead = metaMap.get("lead");
        if (!(lead instanceof Map<?, ?> leadMap)) {
            return null;
        }
        Object stage = leadMap.get("stage");
        return stage == null ? "NEW" : String.valueOf(stage);
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return UUID.fromString(raw.strip());
    }

    private static Integer parsePositiveInt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int value = Integer.parseInt(raw.strip());
            return value > 0 ? value : null;
        }
        catch (NumberFormatException e) {
            return null;
        }
    }

    private static Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw);
        }
        catch (Exception e) {
            return null;
        }
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
