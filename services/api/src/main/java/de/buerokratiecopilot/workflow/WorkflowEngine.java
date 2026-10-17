package de.buerokratiecopilot.workflow;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;
import de.buerokratiecopilot.workflow.WorkflowDefinition.Definition;
import de.buerokratiecopilot.workflow.WorkflowDefinition.Step;

/**
 * Runs a process (spec §16, F-002).
 *
 * <p>The engine is a small loop over data, and two properties are what make it worth having.</p>
 *
 * <p><strong>Restarting the server neither loses nor repeats a step.</strong> The state is the row, not
 * the thread: {@code current_step_key} says where a run is, and the step record is claimed with a guarded
 * update rather than by reading it and hoping. A second attempt finds the step no longer pending and does
 * nothing — which is what makes it safe to call {@link #advance} again after a crash without knowing
 * whether the previous call got anywhere.</p>
 *
 * <p><strong>A delivered event may arrive twice.</strong> Message queues repeat, webhooks retry, and an
 * operator clicks twice. {@link #deliverEvent} therefore only acts when the run is actually waiting for
 * that event: a second delivery finds the run elsewhere and changes nothing.</p>
 *
 * <p>Steps that wait record when they will continue, so nothing has to be scheduled in memory — a run
 * waiting until Friday is a row with a date, not a timer that a restart would drop.</p>
 */
@Service
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    private final JdbcTemplate jdbc;
    private final WorkflowService workflows;

    public WorkflowEngine(JdbcTemplate jdbc, WorkflowService workflows) {
        this.jdbc = jdbc;
        this.workflows = workflows;
    }

    /** A run of a process. */
    public record Run(UUID runId, String status, String currentStep, boolean created) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("runId", runId.toString());
            map.put("status", status);
            map.put("currentStep", currentStep);
            map.put("created", created);
            return map;
        }
    }

    /** What one advance did. */
    public record StepResult(String stepKey, String stepType, String status, String nextStep, String note) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("stepKey", stepKey);
            map.put("stepType", stepType);
            map.put("status", status);
            map.put("nextStep", nextStep);
            map.put("note", note);
            return map;
        }
    }

    /**
     * Starts a run.
     *
     * <p>Idempotent by key: the same key returns the run that exists. A caller that retried after a
     * timeout must not start a second instance of the same process — the second one would send the letter
     * again.</p>
     */
    @Transactional
    public Run start(UUID tenantId, UUID definitionId, String triggerType, Object triggerPayload,
                     String subjectType, UUID subjectId, UUID caseId, String idempotencyKey,
                     UUID requestedBy) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            List<Map<String, Object>> existing = jdbc.queryForList("""
                    select id, status, current_step_key from workflow_runs
                    where tenant_id = ? and idempotency_key = ?
                    """, tenantId, idempotencyKey);
            if (!existing.isEmpty()) {
                Map<String, Object> row = existing.getFirst();
                return new Run((UUID) row.get("id"), String.valueOf(row.get("status")),
                        (String) row.get("current_step_key"), false);
            }
        }

        Integer active = jdbc.queryForObject("""
                select count(*) from workflow_definitions where id = ? and tenant_id = ? and is_active
                """, Integer.class, definitionId, tenantId);
        if (active == null || active == 0) {
            throw ApiException.conflict("Dieser Prozess ist nicht aktiv und wird nicht gestartet");
        }

        WorkflowService.Version version = workflows.currentVersion(tenantId, definitionId);
        if (!"PUBLISHED".equals(version.status())) {
            throw ApiException.conflict("Für diesen Prozess ist keine veröffentlichte Version vorhanden");
        }
        Definition definition = workflows.definitionOf(version.versionId());

        UUID runId = jdbc.queryForObject("""
                insert into workflow_runs (id, tenant_id, definition_id, version_id, status, trigger_type,
                                           trigger_payload, subject_type, subject_id, case_id,
                                           current_step_key, attempt_count, idempotency_key, started_at,
                                           context)
                values (gen_random_uuid(), ?, ?, ?, 'RUNNING', ?, ?::jsonb, ?, ?, ?, ?, 0, ?, now(),
                        '{}'::jsonb)
                returning id
                """, UUID.class, tenantId, definitionId, version.versionId(),
                triggerType == null ? "MANUAL" : triggerType.toUpperCase(java.util.Locale.ROOT),
                WorkflowService.toJson(triggerPayload), subjectType, subjectId, caseId,
                definition.entryStep(), idempotencyKey);

        journal(tenantId, runId, "RUN_STARTED", null, "Der Prozess wurde gestartet");
        log.info("Workflow run {} started on version {} of definition {}", runId, version.version(),
                definitionId);
        return new Run(runId, "RUNNING", definition.entryStep(), true);
    }

    /**
     * Executes the step a run is on and moves it along.
     *
     * <p>Safe to call again after a crash: the step is claimed by an update that only succeeds while the
     * step is still pending, so an interrupted execution is finished rather than repeated.</p>
     */
    @Transactional
    public StepResult advance(UUID tenantId, UUID runId) {
        Map<String, Object> run = requireRun(tenantId, runId);
        String status = String.valueOf(run.get("status"));
        if (!"RUNNING".equals(status) && !"PENDING".equals(status)) {
            throw ApiException.conflict("Dieser Lauf ist im Status " + status + " und läuft nicht weiter");
        }

        String stepKey = (String) run.get("current_step_key");
        if (stepKey == null) {
            return finish(tenantId, runId, "COMPLETED", "Der Prozess ist am Ende angekommen");
        }

        Definition definition = workflows.definitionOf((UUID) run.get("version_id"));
        Step step = definition.step(stepKey);
        if (step == null) {
            // The version is fixed, so this cannot happen unless a definition was tampered with; failing
            // loudly is better than a run that stands still forever.
            return fail(tenantId, runId, "STEP_NOT_FOUND", "Der Schritt '" + stepKey + "' existiert in "
                    + "dieser Version nicht");
        }

        UUID stepId = claimStep(tenantId, runId, step);
        if (stepId == null) {
            // Somebody else is executing it, or it is done. Doing nothing is the correct answer: the
            // guarantee is that a step runs once, not that every call does something.
            return new StepResult(step.key(), step.type(), "ALREADY_TAKEN", null,
                    "Dieser Schritt wurde bereits begonnen");
        }

        long started = System.currentTimeMillis();
        try {
            StepResult result = execute(tenantId, runId, run, definition, step);
            long duration = System.currentTimeMillis() - started;
            jdbc.update("""
                    update workflow_run_steps set status = ?, next_step_key = ?, finished_at = now(),
                           duration_ms = ?, output = ?::jsonb
                    where id = ?
                    """, result.status(), result.nextStep(), duration,
                    WorkflowService.toJson(result.asMap()), stepId);
            jdbc.update("update workflow_runs set attempt_count = attempt_count + 1, updated_at = now() "
                    + "where id = ?", runId);
            return result;
        }
        catch (RuntimeException ex) {
            jdbc.update("""
                    update workflow_run_steps set status = 'FAILED', finished_at = now(),
                           error_code = 'STEP_FAILED', error_message = ? where id = ?
                    """, ex.getMessage(), stepId);
            throw ex;
        }
    }

    private StepResult execute(UUID tenantId, UUID runId, Map<String, Object> run, Definition definition,
                               Step step) {
        switch (step.type()) {
            case "TERMINATE" -> {
                String outcome = step.config() != null && step.config().get("outcome") != null
                        ? String.valueOf(step.config().get("outcome")) : "COMPLETED";
                journal(tenantId, runId, "RUN_TERMINATED", step.key(), "Der Prozess endet hier");
                finish(tenantId, runId, outcome, null);
                return new StepResult(step.key(), step.type(), "SUCCEEDED", null,
                        "Der Prozess wurde beendet");
            }
            case "DELAY", "WAIT" -> {
                return wait(tenantId, runId, step);
            }
            case "CONDITION" -> {
                // The condition is evaluated against the run's trigger payload, because that is what the
                // process was started with; a condition that needs more would be a decision, not a process.
                boolean outcome = evaluate(step, run);
                String next = outcome ? step.onTrue() : step.onFalse();
                moveTo(tenantId, runId, next);
                journal(tenantId, runId, "CONDITION_EVALUATED", step.key(),
                        "Bedingung ergab " + outcome);
                return new StepResult(step.key(), step.type(), "SUCCEEDED", next,
                        "Bedingung ergab " + outcome);
            }
            case "APPROVAL" -> {
                // An approval step stops the run and waits for a person; it does not decide anything.
                jdbc.update("""
                        update workflow_runs set status = 'WAITING_APPROVAL', updated_at = now()
                        where id = ? and tenant_id = ?
                        """, runId, tenantId);
                journal(tenantId, runId, "APPROVAL_REQUESTED", step.key(),
                        "Der Prozess wartet auf eine Freigabe");
                return new StepResult(step.key(), step.type(), "WAITING", step.key(),
                        "Wartet auf eine Freigabe durch eine Person");
            }
            case "TASK_CREATE" -> {
                return taskCreate(tenantId, runId, step);
            }
            default -> {
                // ACTION, NOTIFICATION and the rest are carried out by whoever owns them; the engine
                // records that the step was reached and moves on. Pretending to have done something here
                // would be worse than saying it was reached.
                String next = step.next();
                moveTo(tenantId, runId, next);
                journal(tenantId, runId, "STEP_REACHED", step.key(),
                        "Schritt vom Typ " + step.type() + " erreicht");
                return new StepResult(step.key(), step.type(), "SUCCEEDED", next,
                        "Schritt erreicht");
            }
        }
    }

    /**
     * A step that creates work.
     *
     * <p><strong>What changed.</strong> The engine used to record {@code TASK_CREATE} as "step reached" and
     * move on. A process could be drawn, checked, published and run, and create no task at all — while the
     * editor and the manual both said it would. That is the difference between a drawing tool and an
     * automation, and it is the one gap a person would notice first.</p>
     *
     * <p>The step carries its own title ({@code config.title}) and, optionally, whom to assign
     * ({@code config.assigneeUserId}). The task is linked to the case of the run, so the work appears where
     * the case is, and it is marked as coming from a process rather than from a person.</p>
     */
    private StepResult taskCreate(UUID tenantId, UUID runId, Step step) {
        String title = configString(step, "title");
        if (title == null || title.isBlank()) {
            // The definition is the better name when the step has none, and the step key is the last resort:
            // a task called "task1" tells nobody anything.
            String definition = jdbc.query("""
                    select w.name from workflow_runs r join workflow_definitions w on w.id = r.definition_id
                     where r.id = ?
                    """, rs -> rs.next() ? rs.getString(1) : null, runId);
            title = (definition == null ? "Prozess" : definition) + ": " + step.key();
        }
        Object caseIdRaw = jdbc.query("""
                select case_id from workflow_runs where id = ?
                """, rs -> rs.next() ? rs.getObject(1) : null, runId);
        UUID caseId = caseIdRaw == null ? null : (UUID) caseIdRaw;

        UUID taskId = jdbc.queryForObject("""
                insert into tasks (id, tenant_id, title, status, priority, priority_source, case_id,
                                   source, created_by_user_id)
                values (gen_random_uuid(), ?, ?, 'NEW', 'NORMAL', 'RULE', ?, 'WORKFLOW', null)
                returning id
                """, UUID.class, tenantId, title, caseId);

        String assignee = configString(step, "assigneeUserId");
        if (assignee != null && !assignee.isBlank()) {
            jdbc.update("""
                    insert into task_assignees (id, task_id, tenant_id, user_id, role, assigned_at)
                    values (gen_random_uuid(), ?, ?, cast(? as uuid), 'ASSIGNEE', now())
                    """, taskId, tenantId, assignee);
        }

        String next = step.next();
        moveTo(tenantId, runId, next);
        journal(tenantId, runId, "TASK_CREATED", step.key(), "Aufgabe angelegt: " + title);
        return new StepResult(step.key(), step.type(), "SUCCEEDED", next, "Aufgabe angelegt: " + title);
    }

    /** A configuration value of a step, or null when the step does not carry it. */
    private static String configString(Step step, String key) {
        Object value = step.config() == null ? null : step.config().get(key);
        return value == null ? null : String.valueOf(value);
    }

    private StepResult wait(UUID tenantId, UUID runId, Step step) {
        String waitsFor = step.waitsFor();
        Instant until = Instant.now().plusSeconds(Math.max(0, step.seconds()));
        jdbc.update("""
                update workflow_runs set status = 'WAITING', wait_until = ?, waiting_for_event = ?,
                       updated_at = now()
                where id = ? and tenant_id = ?
                """, java.sql.Timestamp.from(until), waitsFor, runId, tenantId);
        journal(tenantId, runId, "WAITING", step.key(),
                waitsFor == null ? "Wartet bis " + until : "Wartet auf das Ereignis '" + waitsFor + "'");
        return new StepResult(step.key(), step.type(), "WAITING", step.key(),
                waitsFor == null ? "Wartet bis " + until : "Wartet auf '" + waitsFor + "'");
    }

    /**
     * Delivers an event to a run.
     *
     * <p>Only acts when the run is waiting for exactly that event. A second delivery, or a delivery to a
     * run that has moved on, changes nothing — which is what makes a repeated webhook harmless.</p>
     */
    @Transactional
    public boolean deliverEvent(UUID tenantId, UUID runId, String eventName, Object payload) {
        Map<String, Object> run = requireRun(tenantId, runId);
        if (!"WAITING".equals(String.valueOf(run.get("status")))) {
            return false;
        }
        String waitingFor = (String) run.get("waiting_for_event");
        if (waitingFor == null || !waitingFor.equals(eventName)) {
            return false;
        }

        int moved = jdbc.update("""
                update workflow_runs set status = 'RUNNING', waiting_for_event = null, wait_until = null,
                       updated_at = now()
                where id = ? and tenant_id = ? and status = 'WAITING' and waiting_for_event = ?
                """, runId, tenantId, eventName);
        if (moved == 0) {
            return false;
        }

        Definition definition = workflows.definitionOf((UUID) run.get("version_id"));
        Step step = definition.step((String) run.get("current_step_key"));
        if (step != null) {
            moveTo(tenantId, runId, step.next());
        }
        journal(tenantId, runId, "EVENT_RECEIVED", (String) run.get("current_step_key"),
                "Ereignis '" + eventName + "' empfangen");
        return true;
    }

    /**
     * Continues the runs whose waiting time has passed.
     *
     * <p>Called by whatever wakes up periodically; the runs are found in the database, so a restart
     * between two calls loses nothing.</p>
     */
    @Transactional
    public List<UUID> resumeDue(UUID tenantId, int limit) {
        List<UUID> due = jdbc.query("""
                select id from workflow_runs
                where tenant_id = ? and status = 'WAITING' and waiting_for_event is null
                  and wait_until is not null and wait_until <= now()
                order by wait_until limit ?
                """, (rs, row) -> rs.getObject("id", UUID.class), tenantId, Math.max(1, limit));

        for (UUID runId : due) {
            Map<String, Object> run = requireRun(tenantId, runId);
            Definition definition = workflows.definitionOf((UUID) run.get("version_id"));
            Step step = definition.step((String) run.get("current_step_key"));
            jdbc.update("""
                    update workflow_runs set status = 'RUNNING', wait_until = null, updated_at = now()
                    where id = ? and tenant_id = ?
                    """, runId, tenantId);
            if (step != null) {
                moveTo(tenantId, runId, step.next());
            }
            journal(tenantId, runId, "WAIT_OVER", (String) run.get("current_step_key"),
                    "Die Wartezeit ist abgelaufen");
        }
        return due;
    }

    /** Approves a run that is waiting for a person. */
    @Transactional
    public StepResult approve(UUID tenantId, UUID runId, UUID approverId, boolean accepted, String note) {
        Map<String, Object> run = requireRun(tenantId, runId);
        if (!"WAITING_APPROVAL".equals(String.valueOf(run.get("status")))) {
            throw ApiException.conflict("Dieser Lauf wartet nicht auf eine Freigabe");
        }
        if (approverId == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Eine Freigabe braucht eine Person; sonst gibt niemand frei");
        }

        Definition definition = workflows.definitionOf((UUID) run.get("version_id"));
        Step step = definition.step((String) run.get("current_step_key"));

        if (!accepted) {
            journal(tenantId, runId, "APPROVAL_REJECTED", step == null ? null : step.key(), note);
            return fail(tenantId, runId, "REJECTED",
                    note == null ? "Die Freigabe wurde abgelehnt" : note);
        }

        String next = step == null ? null : step.next();
        jdbc.update("""
                update workflow_runs set status = 'RUNNING', updated_at = now()
                where id = ? and tenant_id = ?
                """, runId, tenantId);
        moveTo(tenantId, runId, next);
        journal(tenantId, runId, "APPROVAL_GRANTED", step == null ? null : step.key(), note);
        return new StepResult(step == null ? null : step.key(), "APPROVAL", "SUCCEEDED", next,
                "Freigegeben");
    }

    /** Cancels a run. */
    @Transactional
    public void cancel(UUID tenantId, UUID runId, String reason) {
        int updated = jdbc.update("""
                update workflow_runs set status = 'CANCELLED', finished_at = now(), failure_message = ?,
                       updated_at = now()
                where id = ? and tenant_id = ? and status not in ('COMPLETED', 'CANCELLED', 'FAILED')
                """, reason, runId, tenantId);
        if (updated == 0) {
            throw ApiException.conflict("Dieser Lauf ist bereits beendet");
        }
        journal(tenantId, runId, "RUN_CANCELLED", null, reason);
    }

    /** A run with its steps and events, for a user asking what happened. */
    @Transactional(readOnly = true)
    public Map<String, Object> describe(UUID tenantId, UUID runId) {
        Map<String, Object> run = new LinkedHashMap<>(requireRun(tenantId, runId));
        run.put("steps", jdbc.queryForList("""
                select step_key, step_type, step_index, status, attempt, next_step_key, started_at,
                       finished_at, duration_ms, output ->> 'note' as note
                from workflow_run_steps where run_id = ? and tenant_id = ? order by step_index, created_at
                """, runId, tenantId));
        run.put("events", jdbc.queryForList("""
                select event_type, step_key, message, created_at from workflow_events
                where run_id = ? and tenant_id = ? order by created_at
                """, runId, tenantId));
        return run;
    }

    /** The runs of a tenant in a given status. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> runsInStatus(UUID tenantId, String status, int limit) {
        return jdbc.queryForList("""
                select id, definition_id, status, current_step_key, wait_until, waiting_for_event,
                       case_id, started_at, finished_at
                from workflow_runs where tenant_id = ? and status = ?
                order by started_at desc limit ?
                """, tenantId, status.toUpperCase(java.util.Locale.ROOT), Math.max(1, Math.min(limit, 200)));
    }

    // ------------------------------------------------------------------ internals

    /**
     * Claims a step for execution.
     *
     * <p>The insert happens first with the step marked {@code RUNNING}, and only succeeds if no step with
     * that key and attempt exists yet. Two callers therefore cannot both execute the same step, and a
     * caller that lost the race is told so by an empty result rather than by an exception.</p>
     */
    private UUID claimStep(UUID tenantId, UUID runId, Step step) {
        int attempt = jdbc.queryForObject("""
                select count(*) + 1 from workflow_run_steps where run_id = ? and step_key = ?
                """, Integer.class, runId, step.key());
        int index = jdbc.queryForObject("""
                select count(*) + 1 from workflow_run_steps where run_id = ?
                """, Integer.class, runId);

        List<UUID> claimed = jdbc.query("""
                insert into workflow_run_steps (id, run_id, tenant_id, step_key, step_type, step_index,
                                                status, attempt, input, started_at)
                values (gen_random_uuid(), ?, ?, ?, ?, ?, 'RUNNING', ?, '{}'::jsonb, now())
                on conflict do nothing
                returning id
                """, (rs, row) -> rs.getObject("id", UUID.class), runId, tenantId, step.key(), step.type(),
                index, attempt);
        return claimed.isEmpty() ? null : claimed.getFirst();
    }

    private void moveTo(UUID tenantId, UUID runId, String nextStep) {
        if (nextStep == null || nextStep.isBlank()) {
            finish(tenantId, runId, "COMPLETED", null);
            return;
        }
        jdbc.update("""
                update workflow_runs set current_step_key = ?, updated_at = now()
                where id = ? and tenant_id = ?
                """, nextStep, runId, tenantId);
    }

    private StepResult finish(UUID tenantId, UUID runId, String outcome, String note) {
        String status = "FAILED".equals(outcome) ? "FAILED" : outcome;
        status = switch (outcome) {
            case "COMPLETED", "FAILED", "CANCELLED", "COMPENSATED" -> outcome;
            default -> "COMPLETED";
        };
        jdbc.update("""
                update workflow_runs set status = ?, finished_at = now(), updated_at = now()
                where id = ? and tenant_id = ?
                """, status, runId, tenantId);
        journal(tenantId, runId, "RUN_" + status, null, note);
        return new StepResult(null, null, status, null, note);
    }

    private StepResult fail(UUID tenantId, UUID runId, String code, String message) {
        jdbc.update("""
                update workflow_runs set status = 'FAILED', finished_at = now(), failure_code = ?,
                       failure_message = ?, updated_at = now()
                where id = ? and tenant_id = ?
                """, code, message, runId, tenantId);
        journal(tenantId, runId, "RUN_FAILED", null, message);
        return new StepResult(null, null, "FAILED", null, message);
    }

    /**
     * Evaluates a condition against the run's trigger payload.
     *
     * <p>Deliberately limited to comparison of the values the process was started with. A condition that
     * could do more would be a program, and a process nobody can read is a process nobody can approve.</p>
     */
    @SuppressWarnings("unchecked")
    private boolean evaluate(Step step, Map<String, Object> run) {
        Object field = step.config() == null ? null : step.config().get("field");
        Object expected = step.config() == null ? null : step.config().get("equals");
        if (field == null) {
            return false;
        }
        Object payload = run.get("trigger_payload");
        Object actual = null;
        if (payload instanceof Map<?, ?> map) {
            actual = ((Map<String, Object>) map).get(String.valueOf(field));
        }
        else if (payload instanceof String text && !text.isBlank()) {
            // The column is jsonb and JDBC hands it over as text; without this the condition never saw
            // the payload and always took the false branch.
            Object parsed = WorkflowService.readJson(text);
            if (parsed instanceof Map<?, ?> map) {
                actual = ((Map<String, Object>) map).get(String.valueOf(field));
            }
        }
        if (actual == null && run.containsKey(String.valueOf(field))) {
            actual = run.get(String.valueOf(field));
        }
        return actual != null && String.valueOf(actual).equals(String.valueOf(expected));
    }

    private Map<String, Object> requireRun(UUID tenantId, UUID runId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, status, current_step_key, version_id, trigger_payload::text as trigger_payload,
                       waiting_for_event, case_id, definition_id
                from workflow_runs where id = ? and tenant_id = ?
                """, runId, tenantId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("workflow run");
        }
        return rows.getFirst();
    }

    private void journal(UUID tenantId, UUID runId, String eventType, String stepKey, String message) {
        jdbc.update("""
                insert into workflow_events (id, run_id, tenant_id, event_type, step_key, payload, message)
                values (gen_random_uuid(), ?, ?, ?, ?, '{}'::jsonb, ?)
                """, runId, tenantId, eventType, stepKey, message);
    }
}
