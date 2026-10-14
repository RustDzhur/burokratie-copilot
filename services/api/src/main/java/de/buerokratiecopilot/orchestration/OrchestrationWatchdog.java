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

    public OrchestrationWatchdog(JdbcTemplate jdbc, DocumentProcessingPipeline pipeline,
                                 CaseOrchestrator orchestrator, OwnerNotifier notifier,
                                 @Value("${burokratie.orchestration.watchdog-enabled:true}") boolean enabled,
                                 @Value("${burokratie.orchestration.backfill-days:14}") int backfillDays) {
        this.jdbc = jdbc;
        this.pipeline = pipeline;
        this.orchestrator = orchestrator;
        this.notifier = notifier;
        this.enabled = enabled;
        this.backfillDays = backfillDays;
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
