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
import de.buerokratiecopilot.agents.processes.WorkflowRouter.CaseFacts;
import de.buerokratiecopilot.agents.processes.WorkflowRouter.Route;
import de.buerokratiecopilot.agents.processes.WorkflowRouter.RunFacts;
import de.buerokratiecopilot.assignment.AssignmentService;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.tasks.TaskRepository;
import de.buerokratiecopilot.tasks.TaskService;
import de.buerokratiecopilot.tasks.domain.Task;
import de.buerokratiecopilot.tasks.domain.TaskAssignee;
import de.buerokratiecopilot.workflow.WorkflowEngine;

/**
 * {@code workflow-route} — Workflow-Steuerung.
 *
 * <p>Looks at the state of a case (or a single workflow run) and decides what happens next:
 * a run whose waiting time has passed is resumed, a running run is advanced, a fresh case gets its
 * preset process started, an unassigned task is assigned according to the tenant's role rules.
 * Decisions that need a person — an approval, an external event — are reported as a handover, not
 * invented. The rules themselves live in {@link WorkflowRouter} as a pure function.</p>
 */
@Component
public class WorkflowRouteAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRouteAgent.class);

    private final JdbcTemplate jdbc;
    private final WorkflowEngine engine;
    private final AssignmentService assignment;
    private final TaskRepository tasksRepo;
    private final TaskService tasks;

    public WorkflowRouteAgent(JdbcTemplate jdbc, WorkflowEngine engine, AssignmentService assignment,
                              TaskRepository tasksRepo, TaskService tasks) {
        this.jdbc = jdbc;
        this.engine = engine;
        this.assignment = assignment;
        this.tasksRepo = tasksRepo;
        this.tasks = tasks;
    }

    @Override
    public String id() {
        return "workflow-route";
    }

    @Override
    public AgentResult run(AgentContext context) {
        try {
            return RequestContext.runAs(context.tenantId(), () -> doRun(context));
        }
        catch (IllegalArgumentException e) {
            return AgentResult.failed("Ungültige Eingabe: " + e.getMessage());
        }
        catch (ApiException e) {
            return AgentResult.failed("Workflow-Steuerung abgelehnt: " + e.getMessage());
        }
    }

    private AgentResult doRun(AgentContext context) {
        UUID tenantId = context.tenantId();
        String runIdInput = context.input("workflowRunId");
        if (runIdInput != null && !runIdInput.isBlank()) {
            return routeRun(tenantId, UUID.fromString(runIdInput.trim()), context);
        }
        return routeCase(tenantId, context);
    }

    // ------------------------------------------------------------------ run level

    private AgentResult routeRun(UUID tenantId, UUID runId, AgentContext context) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id::text as id, status, current_step_key, waiting_for_event, wait_until, case_id::text
                from workflow_runs where id = ? and tenant_id = ?
                """, runId, tenantId);
        if (rows.isEmpty()) {
            return AgentResult.skipped("Prozesslauf " + runId + " wurde nicht gefunden");
        }
        Map<String, Object> row = rows.getFirst();
        RunFacts facts = RunFacts.of((String) row.get("id"), String.valueOf(row.get("status")),
                (String) row.get("current_step_key"), (String) row.get("waiting_for_event"),
                toInstant(row.get("wait_until")));

        String status = facts.status();
        Instant now = context.now();
        if ("WAITING".equals(status)) {
            if (facts.waitingForEvent() != null && !facts.waitingForEvent().isBlank()) {
                Map<String, String> meta = new LinkedHashMap<>(Map.of(
                        "action", WorkflowRouter.ACTION_WAIT_EVENT,
                        "targetRunId", facts.runId(),
                        "nextStep", facts.waitingForEvent()));
                return AgentResult.done("Der Lauf wartet auf das Ereignis '"
                        + facts.waitingForEvent() + "' — eine Übergabe ist erst danach möglich",
                        List.of(), meta);
            }
            if (facts.waitUntil() != null && !facts.waitUntil().isAfter(now)) {
                List<UUID> resumed = engine.resumeDue(tenantId, 50);
                List<AgentResult.Produced> produced = new ArrayList<>();
                for (UUID resumedId : resumed) {
                    produced.add(new AgentResult.Produced("workflow_run", resumedId.toString(),
                            "Prozesslauf nach abgelaufener Wartezeit fortgesetzt"));
                }
                boolean resumedThis = resumed.contains(runId);
                Map<String, String> meta = new LinkedHashMap<>(Map.of(
                        "action", WorkflowRouter.ACTION_RESUME_WAIT,
                        "targetRunId", facts.runId(),
                        "resumedRuns", String.valueOf(resumed.size())));
                return AgentResult.done(resumedThis
                                ? "Der Lauf wurde nach abgelaufener Wartezeit fortgesetzt"
                                : "Die Wartezeit des Laufs ist noch nicht abgelaufen",
                        produced, meta);
            }
            Map<String, String> meta = new LinkedHashMap<>(Map.of(
                    "action", WorkflowRouter.ACTION_WAIT_TIME,
                    "targetRunId", facts.runId(),
                    "nextStep", facts.currentStepKey() == null ? "" : facts.currentStepKey()));
            return AgentResult.done("Der Lauf wartet bis "
                    + (facts.waitUntil() == null ? "auf Weiteres" : facts.waitUntil().toString()),
                    List.of(), meta);
        }
        if ("WAITING_APPROVAL".equals(status)) {
            Map<String, String> meta = new LinkedHashMap<>(Map.of(
                    "action", WorkflowRouter.ACTION_AWAIT_APPROVAL,
                    "targetRunId", facts.runId(),
                    "handover", "approver",
                    "nextStep", facts.currentStepKey() == null ? "" : facts.currentStepKey()));
            return AgentResult.done("Der Lauf wartet auf eine Freigabe; die Übergabe geht an die "
                    + "freigabeberechtigte Person", List.of(), meta);
        }
        if ("RUNNING".equals(status) || "PENDING".equals(status)) {
            WorkflowEngine.StepResult step = engine.advance(tenantId, runId);
            Map<String, String> meta = new LinkedHashMap<>(Map.of(
                    "action", WorkflowRouter.ACTION_ADVANCE_RUN,
                    "targetRunId", facts.runId(),
                    "stepKey", step.stepKey() == null ? "" : step.stepKey(),
                    "stepStatus", step.status() == null ? "" : step.status(),
                    "nextStep", step.nextStep() == null ? "" : step.nextStep()));
            return AgentResult.done("Schritt '" + step.stepKey() + "' ausgeführt ("
                    + step.status() + ")" + (step.note() == null ? "" : ": " + step.note()),
                    List.of(new AgentResult.Produced("workflow_step", step.stepKey(),
                            "Schritt " + step.stepKey() + " — " + step.status())), meta);
        }
        return AgentResult.skipped("Der Lauf ist im Status " + status
                + " — es gibt keinen nächsten Schritt");
    }

    // ------------------------------------------------------------------ case level

    private AgentResult routeCase(UUID tenantId, AgentContext context) {
        UUID caseId = resolveCaseId(tenantId, context);
        if (caseId == null) {
            return AgentResult.skipped("Weder caseId noch documentId angegeben — es fehlt der "
                    + "Vorgang, der gesteuert werden soll");
        }

        List<Map<String, Object>> caseRows = jdbc.queryForList("""
                select status, case_type_id from cases where id = ? and tenant_id = ? and deleted_at is null
                """, caseId, tenantId);
        if (caseRows.isEmpty()) {
            return AgentResult.skipped("Vorgang " + caseId + " wurde nicht gefunden");
        }
        String caseStatus = String.valueOf(caseRows.getFirst().get("status"));
        String caseTypeCode = caseTypeCode(caseRows.getFirst().get("case_type_id"));

        CaseFacts facts = new CaseFacts(caseId.toString(), caseStatus, caseTypeCode,
                countPendingAssignmentTasks(tenantId, caseId) > 0,
                countOverdueDeadlines(tenantId, caseId) > 0,
                countUnverifiedDeadlines(tenantId, caseId) > 0);

        List<RunFacts> runs = loadRuns(tenantId, caseId);
        Route route = WorkflowRouter.route(facts, runs, context.now());

        return switch (route.action()) {
            case WorkflowRouter.ACTION_START_PROCESS -> startPreset(tenantId, caseId, route, context);
            case WorkflowRouter.ACTION_ADVANCE_RUN -> advanceRun(tenantId, route);
            case WorkflowRouter.ACTION_RESUME_WAIT -> resumeWait(tenantId, route);
            case WorkflowRouter.ACTION_ASSIGN_PENDING_TASK -> assignPendingTask(tenantId, caseId, context);
            case WorkflowRouter.ACTION_CLOSED -> AgentResult.skipped(route.explanation());
            default -> AgentResult.done(route.explanation(), List.of(), route.meta());
        };
    }

    private AgentResult startPreset(UUID tenantId, UUID caseId, Route route, AgentContext context) {
        String presetCode = route.nextStep();
        List<Map<String, Object>> definitions = jdbc.queryForList("""
                select id from workflow_definitions
                where tenant_id = ? and code = ? and is_active
                limit 1
                """, tenantId, presetCode);
        if (definitions.isEmpty()) {
            return AgentResult.done("Der nächste Schritt wäre der Prozess '" + presetCode
                    + "', er ist für diesen Mandanten aber nicht installiert",
                    List.of(), route.meta());
        }
        UUID definitionId = (UUID) definitions.getFirst().get("id");
        String idempotencyKey = "workflow-route:case:" + caseId;
        WorkflowEngine.Run run = engine.start(tenantId, definitionId, "AGENT",
                Map.of("agent", "workflow-route", "caseId", caseId.toString()), "CASE", caseId, caseId,
                idempotencyKey, context.actorUserId());
        Map<String, String> meta = new LinkedHashMap<>(route.meta());
        meta.put("runId", run.runId().toString());
        meta.put("presetCode", presetCode);
        meta.put("created", String.valueOf(run.created()));
        return AgentResult.done(run.created()
                        ? "Prozess '" + presetCode + "' für den Vorgang gestartet"
                        : "Prozess '" + presetCode + "' läuft bereits — kein zweiter Lauf",
                run.created()
                        ? List.of(new AgentResult.Produced("workflow_run", run.runId().toString(),
                                "Prozess " + presetCode + " gestartet"))
                        : List.of(),
                meta);
    }

    private AgentResult advanceRun(UUID tenantId, Route route) {
        UUID runId = UUID.fromString(route.targetRunId());
        WorkflowEngine.StepResult step = engine.advance(tenantId, runId);
        Map<String, String> meta = new LinkedHashMap<>(route.meta());
        meta.put("stepKey", step.stepKey() == null ? "" : step.stepKey());
        meta.put("stepStatus", step.status() == null ? "" : step.status());
        return AgentResult.done("Schritt '" + step.stepKey() + "' des Laufs " + runId
                + " ausgeführt (" + step.status() + ")",
                List.of(new AgentResult.Produced("workflow_step", step.stepKey(),
                        "Schritt " + step.stepKey() + " — " + step.status())), meta);
    }

    private AgentResult resumeWait(UUID tenantId, Route route) {
        List<UUID> resumed = engine.resumeDue(tenantId, 50);
        List<AgentResult.Produced> produced = new ArrayList<>();
        for (UUID runId : resumed) {
            produced.add(new AgentResult.Produced("workflow_run", runId.toString(),
                    "Prozesslauf nach abgelaufener Wartezeit fortgesetzt"));
        }
        Map<String, String> meta = new LinkedHashMap<>(route.meta());
        meta.put("resumedRuns", String.valueOf(resumed.size()));
        return AgentResult.done(resumed.isEmpty()
                        ? "Kein Lauf war zum Fortsetzen fällig"
                        : resumed.size() + " Prozesslauf/-läufe nach abgelaufener Wartezeit fortgesetzt",
                produced, meta);
    }

    private AgentResult assignPendingTask(UUID tenantId, UUID caseId, AgentContext context) {
        List<Map<String, Object>> pending = jdbc.queryForList("""
                select id from tasks
                where case_id = ? and tenant_id = ? and deleted_at is null and assignment_pending
                  and status not in ('DONE', 'CANCELLED')
                order by due_at asc nulls last, created_at asc
                limit 1
                """, caseId, tenantId);
        if (pending.isEmpty()) {
            return AgentResult.done("Keine Aufgabe ohne Zuständigkeit gefunden",
                    List.of(), Map.of("action", WorkflowRouter.ACTION_ASSIGN_PENDING_TASK));
        }
        UUID taskId = (UUID) pending.getFirst().get("id");
        AssignmentService.Suggestion suggestion = assignment.suggest(tenantId, taskId);
        if (!suggestion.found()) {
            Map<String, String> meta = new LinkedHashMap<>(Map.of(
                    "action", WorkflowRouter.ACTION_ASSIGN_PENDING_TASK,
                    "taskId", taskId.toString(),
                    "handover", "manual-queue",
                    "explanation", suggestion.explanation()));
            return AgentResult.done("Aufgabe " + taskId
                    + " bleibt in der manuellen Verteilung: " + suggestion.explanation(), List.of(), meta);
        }
        Task task = tasksRepo.findByIdAndDeletedAtIsNull(taskId)
                .orElseThrow(() -> ApiException.notFound("task " + taskId));
        tasks.assign(task, UUID.fromString(suggestion.userId()), TaskAssignee.Role.ASSIGNEE,
                context.actorUserId());
        Map<String, String> meta = new LinkedHashMap<>(Map.of(
                "action", WorkflowRouter.ACTION_ASSIGN_PENDING_TASK,
                "taskId", taskId.toString(),
                "handover", suggestion.userId(),
                "displayName", suggestion.displayName() == null ? "" : suggestion.displayName(),
                "score", suggestion.score() == null ? "" : String.valueOf(suggestion.score()),
                "explanation", suggestion.explanation()));
        return AgentResult.done("Aufgabe " + taskId + " an " + suggestion.displayName()
                + " übergeben (" + suggestion.explanation() + ")",
                List.of(new AgentResult.Produced("task_assignment", taskId.toString(),
                        "Zugewiesen an " + suggestion.displayName())), meta);
    }

    // ------------------------------------------------------------------ reads

    private UUID resolveCaseId(UUID tenantId, AgentContext context) {
        String caseIdInput = context.input("caseId");
        if (caseIdInput != null && !caseIdInput.isBlank()) {
            return UUID.fromString(caseIdInput.trim());
        }
        String documentIdInput = context.input("documentId");
        if (documentIdInput == null || documentIdInput.isBlank()) {
            return null;
        }
        UUID documentId = UUID.fromString(documentIdInput.trim());
        List<Map<String, Object>> links = jdbc.queryForList("""
                select cd.case_id from case_documents cd
                where cd.document_id = ?
                """, documentId);
        if (links.isEmpty()) {
            throw ApiException.notFound("Dokument " + documentId + " gehört zu keinem Vorgang");
        }
        return (UUID) links.getFirst().get("case_id");
    }

    private String caseTypeCode(Object caseTypeId) {
        if (caseTypeId == null) {
            return null;
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select code from case_types where id = ?", (UUID) caseTypeId);
        return rows.isEmpty() ? null : String.valueOf(rows.getFirst().get("code"));
    }

    private long countPendingAssignmentTasks(UUID tenantId, UUID caseId) {
        Long count = jdbc.queryForObject("""
                select count(*) from tasks
                where case_id = ? and tenant_id = ? and deleted_at is null and assignment_pending
                  and status not in ('DONE', 'CANCELLED')
                """, Long.class, caseId, tenantId);
        return count == null ? 0 : count;
    }

    private long countOverdueDeadlines(UUID tenantId, UUID caseId) {
        Long count = jdbc.queryForObject("""
                select count(*) from task_deadlines d
                join tasks t on t.id = d.task_id
                where d.case_id = ? and d.tenant_id = ? and d.is_active
                  and t.deleted_at is null and t.status not in ('DONE', 'CANCELLED')
                  and (coalesce(d.due_date, (d.due_at at time zone 'Europe/Berlin')::date) < current_date
                       or (d.due_date is null and d.due_at < now()))
                """, Long.class, caseId, tenantId);
        return count == null ? 0 : count;
    }

    private long countUnverifiedDeadlines(UUID tenantId, UUID caseId) {
        Long count = jdbc.queryForObject("""
                select count(*) from task_deadlines d
                join tasks t on t.id = d.task_id
                where d.case_id = ? and d.tenant_id = ? and d.is_active and d.requires_verification
                  and t.deleted_at is null and t.status not in ('DONE', 'CANCELLED')
                """, Long.class, caseId, tenantId);
        return count == null ? 0 : count;
    }

    private List<RunFacts> loadRuns(UUID tenantId, UUID caseId) {
        return jdbc.query("""
                select id::text as id, status, current_step_key, waiting_for_event, wait_until
                from workflow_runs
                where case_id = ? and tenant_id = ?
                  and status not in ('COMPLETED', 'CANCELLED', 'FAILED')
                order by started_at desc
                """, (rs, row) -> RunFacts.of(rs.getString("id"), rs.getString("status"),
                rs.getString("current_step_key"), rs.getString("waiting_for_event"),
                rs.getTimestamp("wait_until") == null ? null : rs.getTimestamp("wait_until").toInstant()),
                caseId, tenantId);
    }

    private static Instant toInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        return null;
    }
}
