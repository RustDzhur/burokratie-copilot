package de.buerokratiecopilot.agents.core;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.tasks.TaskService;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * Creates the tasks catalogue agents hand work to people with.
 *
 * <p>Every agent-created task has the same two obligations: it needs a stated completion criterion
 * (otherwise "done" is an opinion) and a rerun must not duplicate it. The idempotency check is a
 * lookup by title among the open tasks of the tenant — the title is the business key a person sees
 * on the task board, so it is also the key the second run can recognise.</p>
 *
 * <p>The task is always created with {@link Task.Source#SYSTEM}. Two call shapes exist because the
 * catalogue grew in waves: the first wave passes the individual fields, the second passes a
 * {@link Request} together with the run context and receives the created entity for the run
 * journal. Both share the same table and the same guarantee; neither creates a second task for the
 * same open title.</p>
 */
@Component
public class AgentTaskFactory {

    private static final int MAX_TITLE_LENGTH = 280;
    private static final int MAX_TITLE = 300;
    private static final String COMPLETION_CRITERIA =
            "Ergebnis fachlich geprüft, entschieden und dokumentiert";
    private static final List<String> TERMINAL_STATUSES = List.of("DONE", "CANCELLED");

    private final TaskService tasks;
    private final JdbcTemplate jdbc;

    public AgentTaskFactory(TaskService tasks, JdbcTemplate jdbc) {
        this.tasks = tasks;
        this.jdbc = jdbc;
    }

    /** The task that exists or was created, and whether this call created it. */
    public record Created(UUID taskId, boolean created) {
    }

    /** What an agent asks for; {@code title} and {@code completionCriteria} are the meaningful parts. */
    public record Request(String title, String description, String completionCriteria, Instant dueAt,
                          Task.Priority priority, List<String> tags, UUID caseId,
                          UUID assigneeUserId) {

        public Request {
            tags = tags == null ? List.of() : List.copyOf(tags);
        }
    }

    /**
     * Returns the open task with this title or creates it, reporting whether this call created it.
     *
     * <p>Only open tasks count: a task that was completed last month must not block this month's
     * review. Done and cancelled tasks are therefore excluded from the lookup.</p>
     */
    public Created createIfAbsent(UUID tenantId, UUID actorUserId, String title, String description,
                                  Task.Priority priority, String rationale, Instant dueAt,
                                  List<String> tags) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("An agent task needs a title");
        }
        String safeTitle = truncate(title.strip());
        List<UUID> existing = jdbc.queryForList("""
                select id from tasks
                where tenant_id = ? and deleted_at is null and title = ?
                  and status not in ('DONE', 'CANCELLED')
                order by created_at limit 1
                """, UUID.class, tenantId, safeTitle);
        if (!existing.isEmpty()) {
            return new Created(existing.getFirst(), false);
        }

        Task task = tasks.create(new TaskService.CreateRequest(tenantId, null, null, safeTitle,
                description, priority == null ? Task.Priority.NORMAL : priority, "RULE", rationale,
                dueAt, null, COMPLETION_CRITERIA, false, null, null,
                tags == null ? List.of() : List.copyOf(tags), Task.Source.SYSTEM, null),
                actorUserId);
        return new Created(task.getId(), true);
    }

    /**
     * Creates the task unless an open SYSTEM task with the same title already exists.
     *
     * @return the created task, or empty when the work is already on the board
     */
    public Optional<Task> createIfAbsent(AgentContext context, Request request) {
        if (request == null || request.title() == null || request.title().isBlank()) {
            throw new IllegalArgumentException("An agent task needs a title");
        }
        String title = AgentInputs.truncate(request.title(), MAX_TITLE);
        Integer open = jdbc.queryForObject("""
                select count(*) from tasks
                where tenant_id = ? and deleted_at is null and title = ?
                  and source = 'SYSTEM'
                  and status not in ('DONE', 'CANCELLED')
                """, Integer.class, context.tenantId(), title);
        if (open != null && open > 0) {
            return Optional.empty();
        }
        Task task = tasks.create(new TaskService.CreateRequest(context.tenantId(),
                request.caseId(), null, title, request.description(),
                request.priority() == null ? Task.Priority.NORMAL : request.priority(),
                "RULE", "Aufgabe aus dem Agentenlauf", request.dueAt(), null,
                request.completionCriteria(), false, null, request.assigneeUserId(),
                request.tags(), Task.Source.SYSTEM, null), context.actorUserId());
        return Optional.of(task);
    }

    /** Records a created task in the run journal. */
    public static void produce(AgentContext context, Task task) {
        context.addProduced("task", task.getId().toString(), task.getTitle());
    }

    /** True when the status is one the platform considers finished. */
    public static boolean isTerminal(String status) {
        return status != null && TERMINAL_STATUSES.contains(status.toUpperCase(Locale.ROOT));
    }

    static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= MAX_TITLE_LENGTH ? value
                : value.substring(0, MAX_TITLE_LENGTH - 3) + "...";
    }
}
