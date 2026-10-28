package de.buerokratiecopilot.workflow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import de.buerokratiecopilot.shared.error.ApiException;

/**
 * Runs a process to the next point where it cannot continue on its own.
 *
 * <p>The engine executes one step per {@link WorkflowEngine#advance} call and is deliberately not a loop:
 * each call is its own transaction, so a restart between two steps loses nothing. This class is the loop —
 * it keeps advancing while the run is {@code RUNNING}, and stops the moment the run waits, pauses, fails
 * or ends.</p>
 *
 * <p>It lives beside the engine rather than inside it because of a Spring rule that is also a design rule:
 * a call from one method of a bean to another bypasses the transactional proxy. Driving the loop from a
 * separate bean means every step really is executed in its own transaction, which is the property that
 * makes "advance again after a crash" safe.</p>
 *
 * <p>The loop has a ceiling ({@value #MAX_STEPS}). A definition with a cycle would otherwise run forever,
 * and a request that never returns is worse than a run that stops and says so.</p>
 */
@Service
public class WorkflowRunService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunService.class);

    /** How many steps one call may execute before it stops and reports the ceiling. */
    static final int MAX_STEPS = 200;

    private final WorkflowEngine engine;
    private final JdbcTemplate jdbc;

    public WorkflowRunService(WorkflowEngine engine, JdbcTemplate jdbc) {
        this.engine = engine;
        this.jdbc = jdbc;
    }

    /** Where a run stands after a call that may have executed several steps. */
    public record Outcome(UUID runId, String status, String currentStep, int stepsExecuted,
                          boolean created, boolean stepLimitReached) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("runId", runId.toString());
            map.put("status", status);
            map.put("currentStep", currentStep);
            map.put("stepsExecuted", stepsExecuted);
            map.put("created", created);
            map.put("stepLimitReached", stepLimitReached);
            return map;
        }
    }

    /**
     * Starts a run and executes it until it stops.
     *
     * <p>Starting is idempotent by key, as in the engine: a caller that retried after a timeout gets the run
     * that exists. If that run is still running it is continued; if it is finished nothing happens.</p>
     */
    public Outcome run(UUID tenantId, UUID definitionId, String triggerType, Object triggerPayload,
                       String subjectType, UUID subjectId, UUID caseId, String idempotencyKey,
                       UUID requestedBy) {
        WorkflowEngine.Run run = engine.start(tenantId, definitionId, triggerType, triggerPayload,
                subjectType, subjectId, caseId, idempotencyKey, requestedBy);
        Progress progress = continueRun(tenantId, run.runId());
        return outcome(tenantId, run.runId(), progress, run.created());
    }

    /**
     * Answers the pause of a run and continues it.
     *
     * <p>{@code APPROVE} and {@code SKIP} move past the failed step; {@code RETRY} puts the run back on the
     * same step, and the loop executes it again with the supplied inputs (or the stored ones when the caller
     * supplies none).</p>
     */
    public Outcome resume(UUID tenantId, UUID runId, String decision, Map<String, Object> inputs,
                          UUID actorUserId) {
        engine.resume(tenantId, runId, decision, inputs, actorUserId);
        Progress progress = continueRun(tenantId, runId);
        return outcome(tenantId, runId, progress, false);
    }

    private record Progress(int steps, boolean limitReached) {
    }

    private Progress continueRun(UUID tenantId, UUID runId) {
        int executed = 0;
        for (int i = 0; i < MAX_STEPS; i++) {
            String status = statusOf(tenantId, runId);
            if (!"RUNNING".equals(status) && !"PENDING".equals(status)) {
                return new Progress(executed, false);
            }
            engine.advance(tenantId, runId);
            executed++;
        }
        log.warn("Workflow run {} reached the step ceiling of {} without stopping", runId, MAX_STEPS);
        return new Progress(executed, true);
    }

    private Outcome outcome(UUID tenantId, UUID runId, Progress progress, boolean created) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select status, current_step_key from workflow_runs where id = ? and tenant_id = ?
                """, runId, tenantId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("workflow run");
        }
        Map<String, Object> row = rows.getFirst();
        return new Outcome(runId, String.valueOf(row.get("status")),
                (String) row.get("current_step_key"), progress.steps(), created,
                progress.limitReached());
    }

    private String statusOf(UUID tenantId, UUID runId) {
        List<String> statuses = jdbc.query("""
                select status from workflow_runs where id = ? and tenant_id = ?
                """, (rs, row) -> rs.getString("status"), runId, tenantId);
        if (statuses.isEmpty()) {
            throw ApiException.notFound("workflow run");
        }
        return statuses.getFirst();
    }
}
