package de.buerokratiecopilot.autopilot;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.audit.AuditService;
import de.buerokratiecopilot.audit.domain.AuditEvent;
import de.buerokratiecopilot.autopilot.PlanValidator.StepSpec;
import de.buerokratiecopilot.cases.CaseRepository;
import de.buerokratiecopilot.cases.CaseService;
import de.buerokratiecopilot.cases.domain.Case;
import de.buerokratiecopilot.cases.domain.CaseEvent;
import de.buerokratiecopilot.cases.domain.CaseParticipant;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;
import de.buerokratiecopilot.shared.util.JsonColumns;
import de.buerokratiecopilot.tasks.TaskService;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * The plan of a case: the steps that lead to its goal, their dependencies and the evidence each needs (task CA-02).
 *
 * <p>A plan is a run of the workflow engine's tables ({@code workflow_runs} with the case, {@code workflow_run_steps}) and not
 * a table of its own (decision D-03). A step starts only when everything it depends on is done; it ends only with the evidence
 * it demands; a failure is stored with its reason and the step can be tried again; all of it is in the database, so a restart
 * continues where it stopped. The platform runs by itself only the steps that change nothing outside the workspace; the rest
 * wait for a person.</p>
 */
@Service
public class CasePlanService {

    private static final String DEFINITION_CODE = "case-plan";

    private final JdbcTemplate jdbc;
    private final CaseRepository cases;
    private final CaseService caseService;
    private final TaskService tasks;
    private final AuditService audit;
    private final ObjectMapper json;

    public CasePlanService(JdbcTemplate jdbc, CaseRepository cases, CaseService caseService, TaskService tasks, AuditService audit,
                           ObjectMapper json) {
        this.jdbc = jdbc;
        this.cases = cases;
        this.caseService = caseService;
        this.tasks = tasks;
        this.audit = audit;
        this.json = json;
    }

    private Case writable(UUID caseId, UUID userId, boolean seeAll) {
        Case caseRecord = cases.findById(caseId).filter(c -> c.getDeletedAt() == null).orElseThrow(() -> ApiException.notFound("case"));
        caseService.requireAccess(caseRecord, userId, CaseParticipant.AccessLevel.WRITE, seeAll);
        return caseRecord;
    }

    /** Saves a plan, if it passes the validator; otherwise nothing is saved and the problems are returned in the exception. */
    @Transactional
    public Map<String, Object> savePlan(UUID caseId, List<StepSpec> steps, String source, UUID userId, boolean seeAll) {
        Case caseRecord = writable(caseId, userId, seeAll);
        PlanValidator.Result result = PlanValidator.validate(steps);
        if (!result.isValid()) {
            throw new PlanRejected(result.problems());
        }
        // A plan under way is not replaced behind its back: only a plan nothing has started yet can be replaced.
        List<Map<String, Object>> existing = jdbc.queryForList(
                "select id from workflow_runs where case_id = ? and trigger_type = 'CASE_PLAN' and status in ('RUNNING', 'WAITING', 'PENDING')",
                caseId);
        for (Map<String, Object> run : existing) {
            Integer started = jdbc.queryForObject("select count(*) from workflow_run_steps where run_id = ? and status <> 'PENDING'",
                    Integer.class, run.get("id"));
            if (started != null && started > 0) {
                throw ApiException.conflict("Der Plan des Vorgangs läuft bereits; ein neuer Plan ersetzt ihn erst, wenn er abgeschlossen oder abgebrochen ist");
            }
            jdbc.update("update workflow_runs set status = 'CANCELLED', finished_at = now(), failure_message = 'Durch einen neuen Plan ersetzt' where id = ?",
                    run.get("id"));
        }

        UUID tenantId = caseRecord.getTenantId();
        UUID[] definition = ensureDefinition(tenantId, userId);
        UUID runId = jdbc.queryForObject("""
                insert into workflow_runs (tenant_id, definition_id, version_id, status, trigger_type, subject_type, subject_id, case_id,
                                           started_at, context)
                values (?, ?, ?, 'RUNNING', 'CASE_PLAN', 'CASE', ?, ?, now(), ?::jsonb) returning id
                """, UUID.class, tenantId, definition[0], definition[1], caseId, caseId, toJson(Map.of("source", source == null ? "USER" : source)));
        int index = 0;
        for (StepSpec step : steps) {
            PlanAction action = PlanAction.parse(step.action()).orElseThrow();
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("action", action.name());
            input.put("title", step.title().strip());
            input.put("level", action.level());
            input.put("auto", action.auto());
            input.put("dependsOn", step.dependsOn() == null ? List.of() : step.dependsOn());
            input.put("evidenceRequired", step.evidenceRequired());
            input.put("params", step.params() == null ? Map.of() : step.params());
            jdbc.update("""
                    insert into workflow_run_steps (run_id, tenant_id, step_key, step_type, step_index, status, input)
                    values (?, ?, ?, ?, ?, 'PENDING', ?::jsonb)
                    """, runId, tenantId, step.key(), action.stepType(), index++, toJson(input));
        }
        caseService.record(caseRecord, CaseEvent.EventType.PLAN_PROPOSED, userId, "Plan für den Vorgang gespeichert (" + steps.size() + " Schritte)",
                Map.of("runId", runId.toString(), "source", source == null ? "USER" : source));
        audit.record(AuditService.AuditEntry.builder(AuditEvent.Category.CASE, "CASE_PLAN_SAVED").tenant(tenantId)
                .entity("workflow_runs", runId, null).description("Case plan saved by " + (source == null ? "USER" : source)).build());
        return plan(caseId);
    }

    /** The plan as shown: every step with whether it can start now and what blocks it. */
    @Transactional(readOnly = true)
    public Map<String, Object> plan(UUID caseId) {
        List<Map<String, Object>> runs = jdbc.queryForList("""
                select id, status, started_at, finished_at, context::text as context from workflow_runs
                where case_id = ? and trigger_type = 'CASE_PLAN' order by created_at desc limit 1
                """, caseId);
        Map<String, Object> result = new LinkedHashMap<>();
        if (runs.isEmpty()) {
            result.put("run", null);
            result.put("steps", List.of());
            return result;
        }
        Map<String, Object> run = runs.get(0);
        result.put("run", Map.of("id", run.get("id"), "status", run.get("status")));
        List<Map<String, Object>> steps = JsonColumns.parse(jdbc.queryForList("""
                select step_key, step_type, step_index, status, attempt, input, output, error_code, error_message, started_at, finished_at
                from workflow_run_steps where run_id = ? order by step_index
                """, run.get("id")));
        Map<String, String> statusByKey = new LinkedHashMap<>();
        steps.forEach(step -> statusByKey.put((String) step.get("step_key"), (String) step.get("status")));
        for (Map<String, Object> step : steps) {
            @SuppressWarnings("unchecked")
            Map<String, Object> input = (Map<String, Object>) step.get("input");
            List<String> blockedBy = new ArrayList<>();
            for (Object dependency : (List<?>) input.getOrDefault("dependsOn", List.of())) {
                String status = statusByKey.get(String.valueOf(dependency));
                if (!"SUCCEEDED".equals(status) && !"SKIPPED".equals(status)) {
                    blockedBy.add(String.valueOf(dependency));
                }
            }
            step.put("blockedBy", blockedBy);
            step.put("ready", "PENDING".equals(step.get("status")) && blockedBy.isEmpty());
        }
        result.put("steps", steps);
        return result;
    }

    /** A person (or the platform, for a step that is safe to run itself) starts a step. Refused while a dependency is open. */
    @Transactional
    public Map<String, Object> startStep(UUID caseId, String stepKey, UUID userId, boolean seeAll) {
        Case caseRecord = writable(caseId, userId, seeAll);
        Map<String, Object> step = requireStep(caseId, stepKey);
        requireReady(caseId, step);
        String status = (String) step.get("status");
        if (!"PENDING".equals(status) && !"FAILED".equals(status)) {
            throw ApiException.conflict("Der Schritt ist bereits " + status);
        }
        jdbc.update("""
                update workflow_run_steps set status = 'RUNNING', attempt = attempt + 1, started_at = now(), finished_at = null,
                       error_code = null, error_message = null, updated_at = now()
                where id = ?
                """, step.get("id"));
        caseService.record(caseRecord, CaseEvent.EventType.PLAN_STARTED, userId, "Schritt gestartet: " + title(step),
                Map.of("step", stepKey));
        return plan(caseId);
    }

    /** A step ends. Where it demands evidence, nothing ends without it. */
    @Transactional
    public Map<String, Object> completeStep(UUID caseId, String stepKey, String evidenceType, UUID evidenceId, String note, UUID userId,
                                            boolean seeAll) {
        writable(caseId, userId, seeAll);
        Map<String, Object> step = requireStep(caseId, stepKey);
        Map<String, Object> input = input(step);
        if (!"RUNNING".equals(step.get("status"))) {
            throw ApiException.conflict("Nur ein gestarteter Schritt kann abgeschlossen werden");
        }
        if (Boolean.TRUE.equals(input.get("evidenceRequired")) && evidenceId == null && (note == null || note.isBlank())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Dieser Schritt verlangt einen Nachweis: ein Dokument, eine Nachricht oder eine Notiz");
        }
        Map<String, Object> output = new LinkedHashMap<>();
        if (evidenceType != null || evidenceId != null || note != null) {
            output.put("evidence", Map.of("type", evidenceType == null ? "NOTE" : evidenceType,
                    "id", evidenceId == null ? "" : evidenceId.toString(), "note", note == null ? "" : note));
        }
        jdbc.update("update workflow_run_steps set status = 'SUCCEEDED', output = ?::jsonb, finished_at = now(), updated_at = now() where id = ?",
                toJson(output), step.get("id"));
        finishRunIfDone(caseId);
        return plan(caseId);
    }

    /** A failure is kept with its reason; the step can be started again. */
    @Transactional
    public Map<String, Object> failStep(UUID caseId, String stepKey, String reason, UUID userId, boolean seeAll) {
        writable(caseId, userId, seeAll);
        Map<String, Object> step = requireStep(caseId, stepKey);
        if (!"RUNNING".equals(step.get("status"))) {
            throw ApiException.conflict("Nur ein gestarteter Schritt kann fehlschlagen");
        }
        jdbc.update("""
                update workflow_run_steps set status = 'FAILED', error_code = 'STEP_FAILED', error_message = ?, finished_at = now(), updated_at = now()
                where id = ?
                """, reason == null || reason.isBlank() ? "Der Schritt ist fehlgeschlagen" : reason.strip(), step.get("id"));
        return plan(caseId);
    }

    /** Leaving a step out is a decision with a reason; what depends on it may then go on. */
    @Transactional
    public Map<String, Object> skipStep(UUID caseId, String stepKey, String reason, UUID userId, boolean seeAll) {
        writable(caseId, userId, seeAll);
        Map<String, Object> step = requireStep(caseId, stepKey);
        if (reason == null || reason.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Ein Schritt wird nur mit Begründung übersprungen");
        }
        jdbc.update("update workflow_run_steps set status = 'SKIPPED', error_message = ?, finished_at = now(), updated_at = now() where id = ? and status in ('PENDING', 'FAILED')",
                reason.strip(), step.get("id"));
        finishRunIfDone(caseId);
        return plan(caseId);
    }

    /**
     * Runs what may run by itself: the steps that are ready and that only read or only create something inside the workspace.
     * Everything else stays ready for a person. Safe to call again and again.
     */
    @Transactional
    public int advance(UUID caseId, UUID userId, boolean seeAll) {
        Case caseRecord = writable(caseId, userId, seeAll);
        int ran = 0;
        boolean progressed = true;
        while (progressed) {
            progressed = false;
            Map<String, Object> plan = plan(caseId);
            for (Object raw : (List<?>) plan.get("steps")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> step = (Map<String, Object>) raw;
                @SuppressWarnings("unchecked")
                Map<String, Object> input = (Map<String, Object>) step.get("input");
                if (!Boolean.TRUE.equals(step.get("ready")) || !Boolean.TRUE.equals(input.get("auto"))) {
                    continue;
                }
                runAutomatic(caseRecord, (String) step.get("step_key"), PlanAction.valueOf((String) input.get("action")), input, userId);
                ran++;
                progressed = true;
            }
        }
        if (ran > 0) {
            finishRunIfDone(caseId);
        }
        return ran;
    }

    private void runAutomatic(Case caseRecord, String stepKey, PlanAction action, Map<String, Object> input, UUID userId) {
        Map<String, Object> output = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) input.getOrDefault("params", Map.of());
        Map<String, Object> step = requireStep(caseRecord.getId(), stepKey);
        jdbc.update("update workflow_run_steps set status = 'RUNNING', attempt = attempt + 1, started_at = now(), updated_at = now() where id = ?",
                step.get("id"));
        try {
            switch (action) {
                case CREATE_TASK -> {
                    String title = params.get("taskTitle") == null ? String.valueOf(input.get("title")) : String.valueOf(params.get("taskTitle"));
                    int days = params.get("dueInDays") instanceof Number number ? Math.max(0, number.intValue()) : -1;
                    Task task = tasks.create(new TaskService.CreateRequest(caseRecord.getTenantId(), caseRecord.getId(), null, title,
                            "Aus dem Plan des Vorgangs: " + input.get("title"), Task.Priority.NORMAL, "RULE", "Schritt eines Vorgangsplans",
                            days < 0 ? null : Instant.now().plus(days, ChronoUnit.DAYS), null, null, false, null, caseRecord.getResponsibleUserId(),
                            List.of("plan"), Task.Source.WORKFLOW, null), userId);
                    output.put("taskId", task.getId().toString());
                }
                default -> output.put("note", "Der Schritt ändert nichts außerhalb des Arbeitsbereichs und wurde automatisch ausgeführt");
            }
            jdbc.update("update workflow_run_steps set status = 'SUCCEEDED', output = ?::jsonb, finished_at = now(), updated_at = now() where id = ?",
                    toJson(output), step.get("id"));
        }
        catch (RuntimeException ex) {
            jdbc.update("""
                    update workflow_run_steps set status = 'FAILED', error_code = 'AUTO_FAILED', error_message = ?, finished_at = now(), updated_at = now()
                    where id = ?
                    """, String.valueOf(ex.getMessage()), step.get("id"));
        }
    }

    /**
     * An answer to the case was linked: the steps that wait for a reply end with the letter as their evidence. Returns how many.
     * A step is only ended if what it depends on is done, so an answer cannot skip the sending of the letter it answers.
     */
    @Transactional
    public int onReplyReceived(UUID caseId, UUID mailMessageId) {
        Map<String, Object> plan = plan(caseId);
        int ended = 0;
        for (Object raw : (List<?>) plan.get("steps")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> step = (Map<String, Object>) raw;
            @SuppressWarnings("unchecked")
            Map<String, Object> input = (Map<String, Object>) step.get("input");
            if (!"WAIT_FOR_REPLY".equals(input.get("action")) || !List.of("PENDING", "RUNNING", "WAITING").contains(String.valueOf(step.get("status")))
                    || !((List<?>) step.get("blockedBy")).isEmpty()) {
                continue;
            }
            jdbc.update("""
                    update workflow_run_steps s set status = 'SUCCEEDED', finished_at = now(), updated_at = now(),
                           output = jsonb_build_object('evidence', jsonb_build_object('type', 'MAIL', 'id', ?::text))
                    from workflow_runs r
                    where s.run_id = r.id and r.case_id = ? and r.trigger_type = 'CASE_PLAN' and r.status in ('RUNNING', 'WAITING', 'PENDING')
                      and s.step_key = ? and s.status in ('PENDING', 'RUNNING', 'WAITING')
                    """, mailMessageId.toString(), caseId, step.get("step_key"));
            ended++;
        }
        if (ended > 0) {
            finishRunIfDone(caseId);
        }
        return ended;
    }

    private void finishRunIfDone(UUID caseId) {
        jdbc.update("""
                update workflow_runs r set status = 'COMPLETED', finished_at = now(), updated_at = now()
                where r.case_id = ? and r.trigger_type = 'CASE_PLAN' and r.status = 'RUNNING'
                  and not exists (select 1 from workflow_run_steps s where s.run_id = r.id and s.status not in ('SUCCEEDED', 'SKIPPED'))
                """, caseId);
    }

    private void requireReady(UUID caseId, Map<String, Object> step) {
        Map<String, Object> input = input(step);
        List<String> open = new ArrayList<>();
        for (Object dependency : (List<?>) input.getOrDefault("dependsOn", List.of())) {
            String status = jdbc.query("""
                    select s.status from workflow_run_steps s where s.run_id = ? and s.step_key = ?
                    """, rs -> rs.next() ? rs.getString(1) : null, step.get("run_id"), String.valueOf(dependency));
            if (!"SUCCEEDED".equals(status) && !"SKIPPED".equals(status)) {
                open.add(String.valueOf(dependency));
            }
        }
        if (!open.isEmpty()) {
            throw ApiException.conflict("Der Schritt kann noch nicht beginnen: erst müssen " + String.join(", ", open) + " abgeschlossen sein");
        }
    }

    private Map<String, Object> requireStep(UUID caseId, String stepKey) {
        List<Map<String, Object>> rows = JsonColumns.parse(jdbc.queryForList("""
                select s.id, s.run_id, s.step_key, s.status, s.input from workflow_run_steps s join workflow_runs r on r.id = s.run_id
                where r.case_id = ? and r.trigger_type = 'CASE_PLAN' and r.status in ('RUNNING', 'WAITING', 'PENDING') and s.step_key = ?
                """, caseId, stepKey));
        if (rows.isEmpty()) {
            throw ApiException.notFound("step");
        }
        return rows.get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> input(Map<String, Object> step) {
        return (Map<String, Object>) step.get("input");
    }

    private static String title(Map<String, Object> step) {
        return String.valueOf(input(step).get("title"));
    }

    /** The tenant's own definition that every case plan run points to; created on first use. */
    private UUID[] ensureDefinition(UUID tenantId, UUID userId) {
        List<Map<String, Object>> found = jdbc.queryForList("select id from workflow_definitions where tenant_id = ? and code = ?", tenantId, DEFINITION_CODE);
        UUID definitionId;
        if (found.isEmpty()) {
            definitionId = jdbc.queryForObject("""
                    insert into workflow_definitions (tenant_id, code, name, description, category, is_active, current_version, created_by)
                    values (?, ?, '{"de":"Vorgangsplan","en":"Case plan","ru":"План дела"}'::jsonb,
                            '{"de":"Technische Definition, auf die sich die Pläne der Vorgänge beziehen"}'::jsonb, 'CASE_PLAN', false, 1, ?)
                    returning id
                    """, UUID.class, tenantId, DEFINITION_CODE, userId);
        }
        else {
            definitionId = (UUID) found.get(0).get("id");
        }
        List<UUID> versions = jdbc.queryForList("select id from workflow_versions where definition_id = ? and version = 1", UUID.class, definitionId);
        UUID versionId = versions.isEmpty() ? jdbc.queryForObject("""
                insert into workflow_versions (definition_id, tenant_id, version, status, definition, published_by, published_at, created_by)
                values (?, ?, 1, 'PUBLISHED', '{"entryStep":"plan","steps":[{"key":"plan","type":"TERMINATE","config":{}}]}'::jsonb, ?, now(), ?)
                returning id
                """, UUID.class, definitionId, tenantId, userId, userId) : versions.get(0);
        return new UUID[] { definitionId, versionId };
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("A plan could not be written as JSON", ex);
        }
    }

    /** Thrown when a proposed plan fails the validator; carries what is wrong, and nothing was saved. */
    public static class PlanRejected extends ApiException {
        private final List<PlanValidator.Problem> problems;

        PlanRejected(List<PlanValidator.Problem> problems) {
            super(ErrorCode.INVALID_REQUEST, "Der Plan wurde nicht gespeichert: " + String.join("; ",
                    problems.stream().map(PlanValidator.Problem::message).toList()));
            this.problems = problems;
        }

        public List<PlanValidator.Problem> problems() {
            return problems;
        }
    }
}
