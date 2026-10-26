package de.buerokratiecopilot.agents.processes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import de.buerokratiecopilot.agents.core.AgentRunner;
import de.buerokratiecopilot.cases.CaseService;
import de.buerokratiecopilot.cases.domain.Case;
import de.buerokratiecopilot.deadlines.DeadlineService;
import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.tasks.TaskService;
import de.buerokratiecopilot.tasks.domain.Task;
import de.buerokratiecopilot.workflow.PresetProcesses;

/**
 * End-to-end behaviour of the processes agents against a real PostgreSQL (test profile): real
 * inputs, verified side effects — the task exists once, the approval is not duplicated, the
 * deadline escalation lands exactly once in the notifications, the overview reads the case back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProcessesAgentsIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private AgentRunner runner;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CaseService cases;

    @Autowired
    private TaskService tasks;

    @Autowired
    private DeadlineService deadlines;

    @Autowired
    private PresetProcesses presets;

    private record Session(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private Session register() throws Exception {
        String email = "agent-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Agenten Test","accountType":"COMPANY"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID tenantId = UUID.fromString(JsonPath.read(
                registration.getResponse().getContentAsString(), "$.tenantId"));
        UUID userId = UUID.fromString(JsonPath.read(
                registration.getResponse().getContentAsString(), "$.userId"));
        RequestContext.runAs(tenantId, () -> {
            User user = users.findById(userId).orElseThrow();
            user.markEmailVerified(java.time.Instant.now());
            user.setStatus(User.Status.ACTIVE);
            return users.save(user);
        });
        return new Session(null, tenantId, userId);
    }

    private <T> T asTenant(Session session, java.util.function.Supplier<T> action) {
        return RequestContext.runAs(session.tenantId(), action);
    }

    private Case createCase(Session session, String title, String caseTypeCode) {
        return RequestContext.runAs(session.tenantId(), () -> cases.create(
                new CaseService.CreateRequest(session.tenantId(), title, caseTypeCode, null,
                        Case.Priority.NORMAL, session.userId(), null, null, null, null, null),
                session.userId()));
    }

    @Test
    @DisplayName("task-dispatch creates the task once and assigns the named person")
    void taskDispatchCreatesOnceAndAssigns() throws Exception {
        Session session = register();

        Map<String, String> inputs = Map.of(
                "title", "Rechnung prüfen IT",
                "source", "SYSTEM",
                "assigneeUserId", session.userId().toString());

        AgentRunner.RunResponse first = runner.run(session.tenantId(), session.userId(),
                "task-dispatch", "manual", inputs);
        assertThat(first.ok()).isTrue();

        UUID taskId = UUID.fromString(first.meta().get("taskId"));
        assertThat(asTenant(session, () -> jdbc.queryForObject(
                "select count(*) from tasks where title = 'Rechnung prüfen IT'", Long.class)))
                .isEqualTo(1);
        assertThat(asTenant(session, () -> jdbc.queryForObject(
                "select count(*) from task_assignees where task_id = ? and user_id = ?",
                Long.class, taskId, session.userId()))).isEqualTo(1);

        // Idempotency: the same event again must land on the same task.
        AgentRunner.RunResponse second = runner.run(session.tenantId(), session.userId(),
                "task-dispatch", "manual", inputs);
        assertThat(second.ok()).isTrue();
        assertThat(second.meta().get("reused")).isEqualTo("true");
        assertThat(second.meta().get("taskId")).isEqualTo(taskId.toString());
        assertThat(asTenant(session, () -> jdbc.queryForObject(
                "select count(*) from tasks where title = 'Rechnung prüfen IT'", Long.class)))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("workflow-route starts the preset for a fresh case and advances the run")
    void workflowRouteStartsPresetAndAdvances() throws Exception {
        Session session = register();
        String caseTypeCode = "rechnung-it-" + UUID.randomUUID().toString().substring(0, 8);
        RequestContext.runAs(session.tenantId(), () -> {
            presets.install(session.tenantId(), session.userId());
            jdbc.update("""
                    insert into case_types (id, tenant_id, code, name, description, default_priority,
                                            is_active, sort_order)
                    values (gen_random_uuid(), ?, ?, '{"de":"Rechnung IT"}'::jsonb, '{}'::jsonb,
                            'NORMAL', true, 900)
                    """, session.tenantId(), caseTypeCode);
            return null;
        });

        Case caseRecord = createCase(session, "Rechnungsfall IT", caseTypeCode);

        // No active run yet: the route must start the preset, exactly once.
        AgentRunner.RunResponse started = runner.run(session.tenantId(), session.userId(),
                "workflow-route", "manual", Map.of("caseId", caseRecord.getId().toString()));
        assertThat(started.ok()).isTrue();
        assertThat(started.meta().get("action")).isEqualTo("START_PROCESS");
        UUID runId = UUID.fromString(started.meta().get("runId"));

        AgentRunner.RunResponse again = runner.run(session.tenantId(), session.userId(),
                "workflow-route", "manual", Map.of("caseId", caseRecord.getId().toString()));
        assertThat(again.ok()).isTrue();
        assertThat(again.meta().get("action")).isEqualTo("ADVANCE_RUN");
        assertThat(asTenant(session, () -> jdbc.queryForObject(
                "select count(*) from workflow_runs where case_id = ?",
                Long.class, caseRecord.getId()))).isEqualTo(1);

        // The run itself can be advanced step by step.
        AgentRunner.RunResponse advanced = runner.run(session.tenantId(), session.userId(),
                "workflow-route", "manual", Map.of("workflowRunId", runId.toString()));
        assertThat(advanced.ok()).isTrue();
        assertThat(advanced.meta().get("action")).isEqualTo("ADVANCE_RUN");
    }

    @Test
    @DisplayName("approval requests the same subject once and reports the open queue")
    void approvalRequestsOnceAndReportsStatus() throws Exception {
        Session session = register();
        Case caseRecord = createCase(session, "Freigabefall IT", null);

        Map<String, String> inputs = Map.of(
                "action", "request",
                "subjectType", "CASE",
                "subjectId", caseRecord.getId().toString(),
                "approvalType", "WORKFLOW_PUBLISH",
                "title", "Freigabe Vorgang");

        AgentRunner.RunResponse first = runner.run(session.tenantId(), session.userId(),
                "approval", "manual", inputs);
        assertThat(first.ok()).isTrue();
        assertThat(first.meta().get("reused")).isEqualTo("false");
        UUID approvalId = UUID.fromString(first.meta().get("approvalId"));

        AgentRunner.RunResponse second = runner.run(session.tenantId(), session.userId(),
                "approval", "manual", inputs);
        assertThat(second.ok()).isTrue();
        assertThat(second.meta().get("reused")).isEqualTo("true");
        assertThat(second.meta().get("approvalId")).isEqualTo(approvalId.toString());

        assertThat(asTenant(session, () -> jdbc.queryForObject("""
                select count(*) from approvals
                where subject_type = 'CASE' and subject_id = ? and status = 'PENDING'
                """, Long.class, caseRecord.getId()))).isEqualTo(1);

        AgentRunner.RunResponse status = runner.run(session.tenantId(), session.userId(),
                "approval", "manual", Map.of("action", "status"));
        assertThat(status.ok()).isTrue();
        assertThat(Integer.parseInt(status.meta().get("pendingApprovals"))).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("case-file assembles documents, tasks, events and deadlines of a case")
    void caseFileAssemblesOverview() throws Exception {
        Session session = register();
        Case caseRecord = createCase(session, "Aktenfall IT", null);

        RequestContext.runAs(session.tenantId(), () -> tasks.create(
                new TaskService.CreateRequest(session.tenantId(), caseRecord.getId(), null,
                        "Akte prüfen", "Offene Aufgabe für die Akte", Task.Priority.HIGH, "MANUAL",
                        null, null, null, null, false, null, session.userId(), null,
                        Task.Source.MANUAL, null),
                session.userId()));

        AgentRunner.RunResponse result = runner.run(session.tenantId(), session.userId(),
                "case-file", "manual", Map.of("caseId", caseRecord.getId().toString()));
        assertThat(result.ok()).isTrue();
        assertThat(result.meta().get("overview"))
                .contains(caseRecord.getReference())
                .contains("Akte prüfen");
        assertThat(result.produced()).extracting(p -> p.kind()).contains("case_overview");
    }

    @Test
    @DisplayName("deadline-watch escalates an overdue deadline exactly once")
    void deadlineWatchEscalatesOnce() throws Exception {
        Session session = register();
        Case caseRecord = createCase(session, "Fristenfall IT", null);

        Task task = RequestContext.runAs(session.tenantId(), () -> tasks.create(
                new TaskService.CreateRequest(session.tenantId(), caseRecord.getId(), null,
                        "Frist einhalten", "Aufgabe mit abgelaufener Frist", Task.Priority.HIGH,
                        "MANUAL", null, null, null, null, false, null, session.userId(), null,
                        Task.Source.MANUAL, null),
                session.userId()));
        RequestContext.runAs(session.tenantId(), () -> deadlines.recordManual(session.tenantId(),
                caseRecord.getId(), task.getId(), LocalDate.now().minusDays(1), "IT", session.userId()));

        AgentRunner.RunResponse first = runner.run(session.tenantId(), session.userId(),
                "deadline-watch", "manual", Map.of("leadDays", "7"));
        assertThat(first.ok()).isTrue();
        assertThat(first.meta().get("overdueEscalated")).isEqualTo("1");

        assertThat(asTenant(session, () -> jdbc.queryForObject("""
                select count(*) from notifications where dedup_key like 'deadline-overdue-%'
                """, Long.class))).isEqualTo(1);

        AgentRunner.RunResponse second = runner.run(session.tenantId(), session.userId(),
                "deadline-watch", "manual", Map.of("leadDays", "7"));
        assertThat(second.meta().get("overdueEscalated")).isEqualTo("0");
        assertThat(asTenant(session, () -> jdbc.queryForObject("""
                select count(*) from notifications where dedup_key like 'deadline-overdue-%'
                """, Long.class))).isEqualTo(1);
    }

    @Test
    @DisplayName("calendar-sync honestly reports when no CalDAV connection exists")
    void calendarSyncSkipsWithoutConnection() throws Exception {
        Session session = register();
        AgentRunner.RunResponse result = runner.run(session.tenantId(), session.userId(),
                "calendar-sync", "manual", Map.of());
        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("CalDAV");
    }
}
