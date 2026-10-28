package de.buerokratiecopilot.agents.core;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.calendar.CalendarEventService;
import de.buerokratiecopilot.cases.domain.CaseEvent;
import de.buerokratiecopilot.notifications.NotificationRepository;
import de.buerokratiecopilot.notifications.NotificationService;
import de.buerokratiecopilot.notifications.domain.Notification;
import de.buerokratiecopilot.tasks.TaskRepository;
import de.buerokratiecopilot.tasks.TaskService;
import de.buerokratiecopilot.tasks.domain.Task;
import de.buerokratiecopilot.templates.GeneratedDocumentService;

/**
 * The three things almost every working agent produces, made idempotent once instead of forty times:
 * a task, a generated document and (where it helps) a calendar entry.
 *
 * <p>An agent that runs twice with the same input must not file the same follow-up twice. The task
 * is found by its {@code metadata.agentKey} or by title+source; the generated document by
 * {@code field_values.sourceKey} inside the generated-documents module. Both keys are stored with
 * the object, so the check is a lookup and not a heuristic.</p>
 *
 * <p>This helper creates objects; it never decides whether an agent may succeed. The agent still
 * reports honestly when its data is missing.</p>
 */
@Component
public class AgentWorkspace {

    private static final int MAX_TITLE = 300;

    private final JdbcTemplate jdbc;
    private final TaskService tasks;
    private final TaskRepository taskRepository;
    private final GeneratedDocumentService generatedDocuments;
    private final CalendarEventService calendarEvents;
    private final NotificationService notifications;
    private final NotificationRepository notificationRepository;

    public AgentWorkspace(JdbcTemplate jdbc, TaskService tasks, TaskRepository taskRepository,
                          GeneratedDocumentService generatedDocuments,
                          CalendarEventService calendarEvents, NotificationService notifications,
                          NotificationRepository notificationRepository) {
        this.jdbc = jdbc;
        this.tasks = tasks;
        this.taskRepository = taskRepository;
        this.generatedDocuments = generatedDocuments;
        this.calendarEvents = calendarEvents;
        this.notifications = notifications;
        this.notificationRepository = notificationRepository;
    }

    /** A task an agent asked for; {@code created=false} means it already existed. */
    public record TaskRef(UUID id, boolean created) {
    }

    /** A generated document an agent asked for; {@code created=false} means it already existed. */
    public record DocumentRef(UUID id, boolean created) {
    }

    /** A calendar entry an agent asked for; {@code created=false} means it already existed. */
    public record EventRef(UUID id, boolean created) {
    }

    /** An open task this agent already filed under the same key. */
    public Optional<UUID> findOpenTaskByKey(UUID tenantId, String agentKey) {
        if (agentKey == null || agentKey.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query("""
                select id from tasks
                where tenant_id = ? and deleted_at is null
                  and status not in ('DONE', 'CANCELLED')
                  and metadata->>'agentKey' = ?
                order by created_at limit 1
                """, rs -> rs.next() ? Optional.of(rs.getObject("id", UUID.class)) : Optional.empty(),
                tenantId, agentKey);
    }

    /** An open system task with exactly this title, which is the catalogue's second idempotency key. */
    public Optional<UUID> findOpenTaskByTitle(UUID tenantId, String title) {
        if (title == null || title.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query("""
                select id from tasks
                where tenant_id = ? and deleted_at is null
                  and status not in ('DONE', 'CANCELLED')
                  and title = ? and source = 'SYSTEM'
                order by created_at limit 1
                """, rs -> rs.next() ? Optional.of(rs.getObject("id", UUID.class)) : Optional.empty(),
                tenantId, title.strip());
    }

    /**
     * Creates one system task unless an open one already carries the key (or the same title).
     * {@code metadata} is merged into the task metadata together with the idempotency key.
     */
    public TaskRef ensureTask(AgentContext context, String agentKey, String title, String description,
                              Task.Priority priority, Instant dueAt, List<String> tags,
                              Map<String, Object> metadata) {
        Optional<UUID> existing = findOpenTaskByKey(context.tenantId(), agentKey);
        if (existing.isEmpty()) {
            existing = findOpenTaskByTitle(context.tenantId(), title);
        }
        if (existing.isPresent()) {
            return new TaskRef(existing.get(), false);
        }
        Task task = tasks.create(new TaskService.CreateRequest(context.tenantId(), null, null,
                truncate(title), description, priority == null ? Task.Priority.NORMAL : priority,
                "RULE", "Regel des Agenten " + agentKey, dueAt, null,
                "Vorgang abgeschlossen und Ergebnis dokumentiert", false, null, null,
                tags == null ? List.of() : tags, Task.Source.SYSTEM, null),
                context.actorUserId());
        try {
            Task fresh = taskRepository.findById(task.getId()).orElse(task);
            Map<String, Object> merged = new LinkedHashMap<>(fresh.getMetadata());
            if (metadata != null) {
                merged.putAll(metadata);
            }
            merged.put("agentKey", agentKey);
            fresh.setMetadata(merged);
            taskRepository.save(fresh);
        }
        catch (RuntimeException ignored) {
            // The task exists; only the idempotency marker is missing. A repeated run then falls
            // back on the title check, which is why that check exists.
        }
        return new TaskRef(task.getId(), true);
    }

    public TaskRef ensureTask(AgentContext context, String agentKey, String title, String description,
                              Task.Priority priority, Instant dueAt, List<String> tags) {
        return ensureTask(context, agentKey, title, description, priority, dueAt, tags, Map.of());
    }

    /** An already generated document with this source key, if any. */
    public Optional<UUID> findDocumentBySourceKey(UUID tenantId, String sourceKey) {
        if (sourceKey == null || sourceKey.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query("""
                select id from generated_documents
                where tenant_id = ? and field_values->>'sourceKey' = ?
                order by created_at limit 1
                """, rs -> rs.next() ? Optional.of(rs.getObject("id", UUID.class)) : Optional.empty(),
                tenantId, sourceKey);
    }

    /**
     * Records a generated document once per source key. The body is HTML; a document with unresolved
     * fields is stored in the module's own blocked state instead of being presented as finished.
     */
    public DocumentRef ensureDocument(AgentContext context, String sourceKey, UUID caseId,
                                      UUID contactId, String title, String html,
                                      Map<String, Object> fieldValues, List<String> unresolved) {
        Optional<UUID> existing = findDocumentBySourceKey(context.tenantId(), sourceKey);
        if (existing.isPresent()) {
            return new DocumentRef(existing.get(), false);
        }
        Map<String, Object> values = new LinkedHashMap<>();
        if (fieldValues != null) {
            values.putAll(fieldValues);
        }
        values.put("sourceKey", sourceKey);
        String subjectType = caseId != null ? "CASE" : (contactId != null ? "CONTACT" : null);
        UUID subjectId = caseId != null ? caseId : contactId;
        GeneratedDocumentService.Stored stored = generatedDocuments.record(context.tenantId(),
                new GeneratedDocumentService.Request(null, null, caseId, subjectType, subjectId,
                        truncate(title), "HTML", html, values,
                        unresolved == null ? List.of() : unresolved),
                context.actorUserId());
        return new DocumentRef(stored.id(), true);
    }

    /** A calendar entry, deduplicated by title and date because the calendar has no external key. */
    public EventRef ensureEvent(AgentContext context, String title, LocalDate date,
                                CalendarEventService.Kind kind, UUID caseId, String note) {
        List<UUID> existing = jdbc.query("""
                select id from calendar_events
                where tenant_id = ? and deleted_at is null and title = ? and starts_on = ?
                order by created_at limit 1
                """, (rs, row) -> rs.getObject("id", UUID.class), context.tenantId(), truncate(title),
                date);
        if (!existing.isEmpty()) {
            return new EventRef(existing.getFirst(), false);
        }
        Map<String, Object> created = calendarEvents.create(context.tenantId(),
                new CalendarEventService.Draft(truncate(title), date, null,
                        kind == null ? CalendarEventService.Kind.APPOINTMENT : kind, caseId),
                context.actorUserId(), note, null);
        Object id = created.get("id");
        UUID eventId = id instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(id));
        return new EventRef(eventId, true);
    }

    /**
     * A notification with a dedup key. Returns {@code false} when the key was already used or when
     * there is nobody to notify; a missing recipient is not an error.
     */
    public boolean notify(AgentContext context, UUID userId, String notificationType,
                          Notification.Category category, Notification.Severity severity,
                          String title, String body, String entityType, UUID entityId,
                          String dedupKey) {
        if (userId == null || dedupKey == null || dedupKey.isBlank()) {
            return false;
        }
        if (notificationRepository.findByUserIdAndDedupKey(userId, dedupKey).isPresent()) {
            return false;
        }
        try {
            notifications.notify(new NotificationService.Request(context.tenantId(), userId, null,
                    notificationType, category, severity, title, body, Map.of(), entityType,
                    entityId, null, false, dedupKey));
            return true;
        }
        catch (RuntimeException ex) {
            return false;
        }
    }

    /** Appends one timeline entry to a case; agents never change the case status themselves. */
    public void caseEvent(AgentContext context, UUID caseId, CaseEvent.EventType type, String title,
                          String description, UUID documentId, UUID taskId, UUID contactId,
                          Map<String, Object> metadata) {
        if (caseId == null) {
            return;
        }
        jdbc.update("""
                insert into case_events (id, tenant_id, case_id, event_type, title, description,
                                         occurred_at, actor_user_id, document_id, task_id, contact_id,
                                         metadata)
                values (gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        coalesce(?::jsonb, '{}'::jsonb))
                """, context.tenantId(), caseId, type == null ? "NOTE_ADDED" : type.name(),
                truncate(title), description, java.sql.Timestamp.from(context.now()),
                context.actorUserId(), documentId, taskId, contactId, toJson(metadata));
    }

    public static String truncate(String value) {
        if (value == null) {
            return "";
        }
        String stripped = value.strip();
        return stripped.length() <= MAX_TITLE ? stripped : stripped.substring(0, MAX_TITLE - 3) + "...";
    }

    private String toJson(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(metadata);
        }
        catch (Exception ex) {
            return null;
        }
    }

    /** All produced items as a copy, for callers that stream over them more than once. */
    public static List<AgentResult.Produced> produced(AgentContext context) {
        return new ArrayList<>(context.produced());
    }
}
