package de.buerokratiecopilot.orchestration;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.documents.pipeline.DocumentProcessingPipeline;
import java.time.LocalDate;

import de.buerokratiecopilot.notifications.domain.Notification;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * The part of the platform that notices when nothing happened.
 *
 * <p>Every other component reacts to something. The watchdog reacts to the absence of a reaction, which is
 * what "continue the control and warn the person responsible" requires:</p>
 * <ul>
 *   <li><strong>A document that was never processed</strong> (an event lost, a mailbox import from before
 *       the pipeline was wired) is advanced again; a processed document that never produced a task is
 *       handed to the orchestrator. Nothing may disappear into the archive.</li>
 *   <li><strong>A task past its internal date</strong> is reported once to the person responsible and to the
 *       owners; a task whose reminder date has come is reminded once.</li>
 *   <li><strong>A task nobody is responsible for</strong> is reported to the owners once it has waited a
 *       day, because an unassigned task is not being worked on.</li>
 * </ul>
 * <p>Each message is sent once per task and kind: the mark is stored in the task's metadata, so a restart
 * does not repeat it and a person who completes the task is not nagged.</p>
 */
@Component
public class OrchestrationWatchdog {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationWatchdog.class);
    private static final int BATCH = 25;

    private final JdbcTemplate jdbc;
    private final DocumentProcessingPipeline pipeline;
    private final CaseOrchestrator orchestrator;
    private final OwnerNotifier notifier;
    private final boolean enabled;
    private final int backfillDays;
    /**
     * The days before a deadline on which the office is reminded: seven, three and one by default (§5.4).
     *
     * <p>A single lead time is a single warning. A bookkeeper who is told once, three days out, and then busy for
     * two days, meets the deadline unprepared; told again at three days and again at one, the same date is still
     * in sight when it matters.</p>
     */
    private final java.util.List<Integer> deadlineLeadDays;

    public OrchestrationWatchdog(JdbcTemplate jdbc, DocumentProcessingPipeline pipeline,
                                 CaseOrchestrator orchestrator, OwnerNotifier notifier,
                                 @Value("${burokratie.orchestration.watchdog-enabled:true}") boolean enabled,
                                 @Value("${burokratie.orchestration.backfill-days:14}") int backfillDays,
                                 @Value("${burokratie.orchestration.deadline-lead-days:7,3,1}") String deadlineLeadDays) {
        this.jdbc = jdbc;
        this.pipeline = pipeline;
        this.orchestrator = orchestrator;
        this.notifier = notifier;
        this.enabled = enabled;
        this.backfillDays = backfillDays;
        this.deadlineLeadDays = parseLeadDays(deadlineLeadDays);
    }

    @Scheduled(fixedDelayString = "${burokratie.orchestration.watchdog-poll-ms:60000}",
            initialDelayString = "${burokratie.orchestration.watchdog-initial-delay-ms:45000}")
    public void run() {
        if (!enabled) {
            return;
        }
        guarded("stuck documents", this::recoverDocuments);
        guarded("overdue tasks", this::reportOverdue);
        guarded("reminders", this::remind);
        // Deadlines derived from documents had nobody telling anyone: remind_at was only ever set for tasks
        // the orchestrator created with a lead time that was still in the future, so a deadline that arrived
        // late, or one a person entered, sat in the calendar and never produced a notification.
        guarded("deadline reminders", () -> {
            for (int lead : deadlineLeadDays) {
                remindDeadlines(lead);
            }
        });
        guarded("unassigned tasks", this::reportUnassigned);
    }

    private void guarded(String what, Runnable step) {
        try {
            step.run();
        }
        catch (RuntimeException ex) {
            // One failing check must not stop the others, and must never kill the scheduler thread.
            log.error("Watchdog check '{}' failed", what, ex);
        }
    }

    record Ref(UUID id, UUID tenantId) {
    }

    /** Public so a test can run one cycle without waiting for the scheduler. */
    public int recoverDocuments() {
        return recoverDocuments(null);
    }

    /** One cycle, optionally limited to one tenant (for the operator who looks at a single customer). */
    public int recoverDocuments(UUID onlyTenant) {
        int handled = 0;
        List<Ref> unprocessed = jdbc.query("""
                select d.id, d.tenant_id from documents d
                where d.processing_status = 'RECEIVED' and d.deleted_at is null
                  and d.received_at < now() - interval '2 minutes'
                  and not exists (select 1 from document_processing_steps s
                                  where s.document_id = d.id and s.status = 'FAILED')
                  and not exists (select 1 from outbox_events e
                                  where e.aggregate_id = d.id and e.event_type = 'document.received'
                                    and e.status in ('PENDING', 'PROCESSING', 'FAILED'))
                  and (cast(? as uuid) is null or d.tenant_id = cast(? as uuid))
                order by d.received_at limit ?
                """, (rs, i) -> new Ref(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)),
                onlyTenant, onlyTenant, BATCH);
        for (Ref ref : unprocessed) {
            try {
                RequestContext.runAs(ref.tenantId(), () -> pipeline.advance(ref.id()));
                handled++;
            }
            catch (RuntimeException ex) {
                log.warn("Document {} could not be advanced by the watchdog: {}", ref.id(), ex.getMessage());
            }
        }

        List<Ref> untouched = jdbc.query("""
                select d.id, d.tenant_id from documents d
                where d.processing_status = 'READY' and d.deleted_at is null
                  and d.updated_at < now() - interval '1 minute'
                  and d.updated_at > now() - make_interval(days => ?)
                  and not exists (select 1 from tasks t where t.source_document_id = d.id
                                  and t.source = 'DOCUMENT' and t.deleted_at is null)
                  and not exists (select 1 from audit_events a where a.entity_type = 'documents'
                                  and a.entity_id = d.id and a.action = 'DOCUMENT_ORCHESTRATED')
                  and not exists (select 1 from outbox_events e
                                  where e.aggregate_id = d.id and e.event_type = 'document.processing_completed'
                                    and e.status in ('PENDING', 'PROCESSING', 'FAILED'))
                  and (cast(? as uuid) is null or d.tenant_id = cast(? as uuid))
                order by d.updated_at limit ?
                """, (rs, i) -> new Ref(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)),
                backfillDays, onlyTenant, onlyTenant, BATCH);
        for (Ref ref : untouched) {
            try {
                RequestContext.runAs(ref.tenantId(), () -> orchestrator.orchestrate(ref.id()));
                handled++;
            }
            catch (RuntimeException ex) {
                log.warn("Document {} could not be organised by the watchdog: {}", ref.id(), ex.getMessage());
            }
        }
        return handled;
    }

    record TaskRef(UUID id, UUID tenantId, String title, UUID documentId, List<UUID> assignees) {
    }

    private List<TaskRef> tasksWhere(String condition, UUID onlyTenant) {
        return jdbc.query("""
                select t.id, t.tenant_id, t.title, t.source_document_id,
                       coalesce((select array_agg(a.user_id) from task_assignees a where a.task_id = t.id
                                 and a.completed_at is null), '{}') as assignees
                from tasks t
                where t.deleted_at is null and t.status not in ('DONE', 'CANCELLED')
                  and %s
                  and (cast(? as uuid) is null or t.tenant_id = cast(? as uuid))
                order by t.created_at limit ?
                """.formatted(condition), (rs, i) -> {
            java.sql.Array array = rs.getArray("assignees");
            List<UUID> assignees = array == null ? List.of() : List.of((UUID[]) array.getArray());
            return new TaskRef(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                    rs.getString("title"), rs.getObject("source_document_id", UUID.class), assignees);
        }, onlyTenant, onlyTenant, BATCH);
    }

    private void mark(UUID taskId, String key) {
        jdbc.update("update tasks set metadata = metadata || jsonb_build_object(?, now()::text) where id = ?",
                key, taskId);
    }

    private String link(TaskRef task) {
        return task.documentId() == null ? "/tasks" : "/documents/" + task.documentId();
    }

    /** The same rule for a deadline: its source document if it has one, otherwise the task list. */
    private String link(DeadlineRef deadline) {
        return deadline.documentId() == null ? "/tasks" : "/documents/" + deadline.documentId();
    }

    public int reportOverdue() {
        return reportOverdue(null);
    }

    public int reportOverdue(UUID onlyTenant) {
        List<TaskRef> overdue = tasksWhere(
                "t.due_at < now() and not jsonb_exists(t.metadata, 'overdueNotifiedAt')", onlyTenant);
        for (TaskRef task : overdue) {
            RequestContext.runAs(task.tenantId(), () -> {
                String body = "Die Aufgabe „" + task.title() + "“ ist über den internen Termin hinaus offen.";
                if (task.assignees().isEmpty()) {
                    notifier.tell(task.tenantId(), Notification.Category.MISSED_DEADLINE,
                            Notification.Severity.WARNING, "task.overdue", "Aufgabe überfällig", body, "tasks",
                            task.id(), link(task), "task-overdue-" + task.id(), java.util.Map.of("taskTitle", task.title()));
                }
                else {
                    notifier.tell(task.tenantId(), task.assignees(), Notification.Category.MISSED_DEADLINE,
                            Notification.Severity.WARNING, "task.overdue", "Aufgabe überfällig", body, "tasks",
                            task.id(), link(task), "task-overdue-" + task.id(), java.util.Map.of("taskTitle", task.title()));
                }
                mark(task.id(), "overdueNotifiedAt");
            });
        }
        return overdue.size();
    }

    public int remind() {
        return remind(null);
    }

    public int remind(UUID onlyTenant) {
        List<TaskRef> due = tasksWhere("t.remind_at is not null and t.remind_at < now()"
                + " and not jsonb_exists(t.metadata, 'remindedAt') and not jsonb_exists(t.metadata, 'overdueNotifiedAt')",
                onlyTenant);
        for (TaskRef task : due) {
            RequestContext.runAs(task.tenantId(), () -> {
                String body = "Die Aufgabe „" + task.title() + "“ wird bald fällig.";
                if (task.assignees().isEmpty()) {
                    notifier.tell(task.tenantId(), Notification.Category.DEADLINE, Notification.Severity.NOTICE,
                            "task.reminder", "Erinnerung an eine Aufgabe", body, "tasks", task.id(), link(task),
                            "task-reminder-" + task.id(), java.util.Map.of("taskTitle", task.title()));
                }
                else {
                    notifier.tell(task.tenantId(), task.assignees(), Notification.Category.DEADLINE,
                            Notification.Severity.NOTICE, "task.reminder", "Erinnerung an eine Aufgabe", body,
                            "tasks", task.id(), link(task), "task-reminder-" + task.id(), java.util.Map.of("taskTitle", task.title()));
                }
                mark(task.id(), "remindedAt");
            });
        }
        return due.size();
    }

    /** One deadline close enough to be worth telling somebody about. */
    record DeadlineRef(UUID deadlineId, UUID taskId, UUID tenantId, String title, UUID documentId,
                       LocalDate dueDate, List<UUID> assignees) {
    }

    /**
     * Reads the configured lead days, tolerating both the old single value ("3") and the list ("7,3,1").
     *
     * <p>An empty or unreadable value falls back to three days rather than to nothing: a deployment that cannot
     * parse its own configuration should still warn about a deadline.</p>
     */
    private static java.util.List<Integer> parseLeadDays(String csv) {
        java.util.List<Integer> leads = new java.util.ArrayList<>();
        if (csv != null) {
            for (String part : csv.split(",")) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                try {
                    int value = Integer.parseInt(trimmed);
                    if (value >= 0 && !leads.contains(value)) {
                        leads.add(value);
                    }
                }
                catch (NumberFormatException ignored) {
                    // A broken entry must not cost the other warnings, so it is skipped, not thrown.
                }
            }
        }
        if (leads.isEmpty()) {
            leads.add(3);
        }
        leads.sort(java.util.Comparator.reverseOrder());
        return java.util.List.copyOf(leads);
    }

    public int remindDeadlines(int leadDays) {
        return remindDeadlines(leadDays, null);
    }

    /**
     * Reminds the responsible people about deadlines that are due within {@code leadDays}.
     *
     * <p><strong>Why this is separate from {@link #remind()}.</strong> That one follows
     * {@code tasks.remind_at}, which the orchestrator sets to one day before the internal due date and only
     * when that date is still in the future — so a document that arrives late, or a deadline a person enters,
     * never gets a reminder and never reaches anybody outside the calendar. This one reads the deadlines
     * themselves.</p>
     *
     * <p>Repeating is prevented by the notification's dedup key, checked in the query: the same deadline
     * reminds once, however often the watchdog runs.</p>
     */
    public int remindDeadlines(int leadDays, UUID onlyTenant) {
        List<DeadlineRef> due = jdbc.query("""
                select d.id as deadline_id, t.id as task_id, t.tenant_id, t.title, t.source_document_id,
                       d.due_date,
                       coalesce((select array_agg(a.user_id) from task_assignees a
                                  where a.task_id = t.id and a.completed_at is null), '{}') as assignees
                  from task_deadlines d
                  join tasks t on t.id = d.task_id
                 where d.is_active
                   and t.deleted_at is null and t.status not in ('DONE', 'CANCELLED')
                   and d.due_date between current_date and current_date + make_interval(days => ?)
                   and (cast(? as uuid) is null or t.tenant_id = cast(? as uuid))
                   and not exists (select 1 from notifications n
                                    where n.dedup_key = 'deadline-reminder-' || d.id || '-' || ?)
                 order by d.due_date
                """, (rs, i) -> new DeadlineRef(
                        rs.getObject("deadline_id", UUID.class),
                        rs.getObject("task_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("title"),
                        rs.getObject("source_document_id", UUID.class),
                        rs.getDate("due_date") == null ? null : rs.getDate("due_date").toLocalDate(),
                        java.util.Arrays.asList((UUID[]) rs.getArray("assignees").getArray())),
                leadDays, onlyTenant, onlyTenant, leadDays);

        for (DeadlineRef deadline : due) {
            RequestContext.runAs(deadline.tenantId(), () -> {
                String body = "Die Frist für „" + deadline.title() + "“ läuft am "
                        + deadline.dueDate() + " ab.";
                java.util.Map<String, Object> parameters = java.util.Map.of(
                        "taskTitle", deadline.title(),
                        "dueDate", String.valueOf(deadline.dueDate()));
                // The dedup key is what makes this once per deadline; the query already excludes the ones
                // that were reminded.
                // One reminder per deadline and per lead day: without the lead in the key the first warning
                // would silence the other two, which is the whole point of having three.
                String dedupKey = "deadline-reminder-" + deadline.deadlineId() + "-" + leadDays;
                if (deadline.assignees().isEmpty()) {
                    notifier.tell(deadline.tenantId(), Notification.Category.DEADLINE,
                            Notification.Severity.WARNING, "task.deadline_approaching",
                            "Frist naht", body, "tasks", deadline.taskId(), link(deadline),
                            dedupKey, parameters);
                }
                else {
                    notifier.tell(deadline.tenantId(), deadline.assignees(),
                            Notification.Category.DEADLINE, Notification.Severity.WARNING,
                            "task.deadline_approaching", "Frist naht", body, "tasks",
                            deadline.taskId(), link(deadline), dedupKey, parameters);
                }
            });
        }
        return due.size();
    }

    public int reportUnassigned() {
        return reportUnassigned(null);
    }

    public int reportUnassigned(UUID onlyTenant) {
        List<TaskRef> open = tasksWhere("t.assignment_pending and t.created_at < now() - interval '1 day'"
                + " and not jsonb_exists(t.metadata, 'unassignedNotifiedAt')", onlyTenant);
        for (TaskRef task : open) {
            RequestContext.runAs(task.tenantId(), () -> {
                notifier.tell(task.tenantId(), Notification.Category.TASK, Notification.Severity.WARNING,
                        "task.unassigned", "Aufgabe ohne Zuständigkeit",
                        "Die Aufgabe „" + task.title() + "“ hat seit über einem Tag keine zuständige Person.",
                        "tasks", task.id(), link(task), "task-unassigned-" + task.id(), java.util.Map.of("taskTitle", task.title()));
                mark(task.id(), "unassignedNotifiedAt");
            });
        }
        return open.size();
    }
}
