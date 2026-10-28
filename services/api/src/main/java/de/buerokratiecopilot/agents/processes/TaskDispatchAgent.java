package de.buerokratiecopilot.agents.processes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.assignment.AssignmentService;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.tasks.TaskService;
import de.buerokratiecopilot.tasks.domain.Task;
import de.buerokratiecopilot.tasks.domain.TaskAssignee;

/**
 * {@code task-dispatch} — Aufgaben-Verteilung.
 *
 * <p>Creates tasks from events and assigns them by the tenant's role rules, on top of the tasks and
 * assignment modules. Idempotent: a task for the same title, source and source document is created
 * once — a repeated event (a retried webhook, a re-run) lands on the existing task instead of
 * duplicating it. When no rule or no person reaches the minimum score, the task honestly stays in
 * the manual distribution queue instead of being assigned to whoever came first.</p>
 */
@Component
public class TaskDispatchAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(TaskDispatchAgent.class);

    private final TaskService tasks;
    private final AssignmentService assignment;
    private final JdbcTemplate jdbc;

    public TaskDispatchAgent(TaskService tasks, AssignmentService assignment, JdbcTemplate jdbc) {
        this.tasks = tasks;
        this.assignment = assignment;
        this.jdbc = jdbc;
    }

    @Override
    public String id() {
        return "task-dispatch";
    }

    @Override
    public AgentResult run(AgentContext context) {
        return RequestContext.runAs(context.tenantId(), () -> doRun(context));
    }

    private AgentResult doRun(AgentContext context) {
        UUID tenantId = context.tenantId();
        String title = context.input("title");
        if (title == null || title.isBlank()) {
            return AgentResult.skipped("title fehlt — eine Aufgabe ohne Titel kann niemand annehmen");
        }
        title = title.strip();

        try {
            Task.Priority priority = TaskDispatchInput.parsePriority(context.input("priority"));
            Task.Source source = TaskDispatchInput.parseSource(context.input("source"), Task.Source.SYSTEM);
            Instant dueAt = TaskDispatchInput.parseDueAt(context.input("dueAt"));
            boolean autoAssign = TaskDispatchInput.parseAutoAssign(context.input("autoAssign"), true);
            UUID caseId = parseUuid(context.input("caseId"));
            UUID sourceDocumentId = parseUuid(context.input("sourceDocumentId"));
            UUID assigneeUserId = parseUuid(context.input("assigneeUserId"));

            // Idempotency: the same event must not create the same task twice.
            Map<String, Object> existing = findExisting(tenantId, title, source, sourceDocumentId);
            if (existing != null) {
                Map<String, String> meta = new LinkedHashMap<>(Map.of(
                        "taskId", String.valueOf(existing.get("id")),
                        "reused", "true",
                        "status", String.valueOf(existing.get("status")),
                        "source", source.name()));
                return AgentResult.done("Für '" + title + "' existiert bereits die Aufgabe "
                        + existing.get("id") + " — sie wird nicht doppelt angelegt", List.of(), meta);
            }

            Task task = tasks.create(new TaskService.CreateRequest(tenantId, caseId, null, title,
                    context.input("description"),
                    priority == null ? Task.Priority.NORMAL : priority,
                    "RULE", "Von task-dispatch nach Ereignisregeln gesetzt", dueAt, null,
                    context.input("completionCriteria"), false, null, null,
                    TaskDispatchInput.parseTags(context.input("tags")), source, sourceDocumentId),
                    context.actorUserId());

            List<AgentResult.Produced> produced = new ArrayList<>();
            produced.add(new AgentResult.Produced("task", task.getId().toString(),
                    "Aufgabe '" + title + "' angelegt"));
            Map<String, String> meta = new LinkedHashMap<>(Map.of(
                    "taskId", task.getId().toString(),
                    "reused", "false",
                    "priority", task.getPriority().name(),
                    "source", task.getSource().name()));

            String eventType = context.input("eventType");
            if (eventType != null && !eventType.isBlank()) {
                meta.put("eventType", eventType);
            }

            if (assigneeUserId != null) {
                tasks.assign(task, assigneeUserId, TaskAssignee.Role.ASSIGNEE, context.actorUserId());
                meta.put("assigned", "true");
                meta.put("assignedBy", "input");
                meta.put("assigneeUserId", assigneeUserId.toString());
                produced.add(new AgentResult.Produced("task_assignment", task.getId().toString(),
                        "Direkt zugewiesen an " + assigneeUserId));
                return AgentResult.done("Aufgabe '" + title + "' angelegt und an " + assigneeUserId
                        + " zugewiesen", produced, meta);
            }

            if (autoAssign) {
                AssignmentService.Suggestion suggestion = assignment.suggest(tenantId, task.getId());
                if (suggestion.found()) {
                    tasks.assign(task, UUID.fromString(suggestion.userId()),
                            TaskAssignee.Role.ASSIGNEE, context.actorUserId());
                    meta.put("assigned", "true");
                    meta.put("assignedBy", "rules");
                    meta.put("assigneeUserId", suggestion.userId());
                    meta.put("displayName", suggestion.displayName() == null ? ""
                            : suggestion.displayName());
                    meta.put("score", suggestion.score() == null ? "" : String.valueOf(suggestion.score()));
                    meta.put("explanation", suggestion.explanation());
                    produced.add(new AgentResult.Produced("task_assignment", task.getId().toString(),
                            "Regelbasiert zugewiesen an " + suggestion.displayName()));
                    return AgentResult.done("Aufgabe '" + title + "' angelegt und nach den "
                            + "Rollenregeln an " + suggestion.displayName() + " zugewiesen",
                            produced, meta);
                }
                meta.put("assigned", "false");
                meta.put("explanation", suggestion.explanation());
                return AgentResult.done("Aufgabe '" + title + "' angelegt, bleibt aber in der "
                        + "manuellen Verteilung: " + suggestion.explanation(), produced, meta);
            }

            meta.put("assigned", "false");
            return AgentResult.done("Aufgabe '" + title + "' angelegt; die automatische Zuweisung "
                    + "ist ausgeschaltet", produced, meta);
        }
        catch (IllegalArgumentException e) {
            return AgentResult.failed("Ungültige Eingabe: " + e.getMessage());
        }
    }

    private Map<String, Object> findExisting(UUID tenantId, String title, Task.Source source,
                                             UUID sourceDocumentId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id::text as id, status from tasks
                where tenant_id = ? and deleted_at is null and title = ? and source = ?
                  and ((source_document_id is null and cast(? as uuid) is null)
                       or source_document_id = ?)
                  and status not in ('DONE', 'CANCELLED')
                order by created_at desc limit 1
                """, tenantId, title, source.name(), sourceDocumentId, sourceDocumentId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return UUID.fromString(raw.trim());
    }
}
