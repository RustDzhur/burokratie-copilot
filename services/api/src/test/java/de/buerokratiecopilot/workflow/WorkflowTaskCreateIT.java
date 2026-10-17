package de.buerokratiecopilot.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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

import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * A process that says it creates a task creates one (spec §16).
 *
 * <p><strong>What was wrong.</strong> The engine recorded {@code TASK_CREATE} as "step reached" and moved on,
 * with a comment admitting that pretending would be worse than saying so. The comment was honest and the
 * behaviour was still wrong: a process could be drawn, checked, published and run, and no task appeared,
 * although the editor shows a step that says it creates one. Reporting a missing feature honestly is not the
 * same as having it.</p>
 *
 * <p>The test publishes a process whose middle step is {@code TASK_CREATE} and runs it. The assertion is on
 * the task and on its title, not on the journal — the journal was always right, which is why nothing caught
 * this before.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkflowTaskCreateIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WorkflowEngine engine;

    @Autowired
    private WorkflowService workflows;

    private record Tenant(UUID tenantId, UUID userId) {
    }

    private Tenant register() throws Exception {
        String email = "wfc-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Prozess Test","accountType":"COMPANY"}
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
        return new Tenant(tenantId, userId);
    }

    private UUID publish(Tenant owner, String code, Map<String, Object> definition) {
        UUID definitionId = RequestContext.runAs(owner.tenantId(),
                () -> workflows.define(owner.tenantId(), code, code, "Ein Prozess", "MANUAL", Map.of(),
                        owner.userId())).definitionId();
        RequestContext.runAs(owner.tenantId(),
                () -> workflows.publish(owner.tenantId(), definitionId, definition, Map.of(), null,
                        owner.userId()));
        return definitionId;
    }

    private int taskCount(Tenant owner) {
        Integer count = jdbc.queryForObject(
                "select count(*) from tasks where tenant_id = ?", Integer.class, owner.tenantId());
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("a TASK_CREATE step creates the task the process promises")
    void aTaskCreateStepCreatesTheTask() throws Exception {
        Tenant owner = register();
        int before = taskCount(owner);
        assertThat(before).as("a fresh account has no tasks").isZero();

        Map<String, Object> definition = Map.of("entryStep", "start", "steps", List.of(
                Map.of("key", "start", "type", "ACTION", "config", Map.of("next", "make")),
                Map.of("key", "make", "type", "TASK_CREATE",
                        "config", Map.of("next", "end", "title", "Rechnung prüfen (aus Prozess)")),
                Map.of("key", "end", "type", "TERMINATE", "config", Map.of())));
        UUID definitionId = publish(owner, "wf-task-" + UUID.randomUUID().toString().substring(0, 6),
                definition);

        WorkflowEngine.Run run = RequestContext.runAs(owner.tenantId(),
                () -> engine.start(owner.tenantId(), definitionId, "MANUAL", Map.of(), null, null, null,
                        null, owner.userId()));

        assertThat(run.created()).as("the process started").isTrue();
        // start() only creates the run; the steps execute as it advances.
        // start() only creates the run; the steps execute as it advances. advance() returns the step it just
        // ran, so the loop is fixed: the process has three steps.
        for (int i = 0; i < 5; i++) {
            try {
                RequestContext.runAs(owner.tenantId(), () -> engine.advance(owner.tenantId(), run.runId()));
            }
            catch (RuntimeException done) {
                // A finished run refuses to advance; that is the end of this test, not a failure.
                break;
            }
        }
        // Before the change the run also succeeded - and created nothing. This is the assertion that would
        // have caught it.
        assertThat(taskCount(owner))
                .as("the process promised a task and must have created one")
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select title from tasks where tenant_id = ? limit 1",
                String.class, owner.tenantId()))
                .isEqualTo("Rechnung prüfen (aus Prozess)");
    }

    @Test
    @DisplayName("a step without a title still creates a task that says what it is")
    void aStepWithoutATitleNamesTheProcess() throws Exception {
        Tenant owner = register();
        Map<String, Object> definition = Map.of("entryStep", "start", "steps", List.of(
                Map.of("key", "start", "type", "ACTION", "config", Map.of("next", "make")),
                Map.of("key", "make", "type", "TASK_CREATE", "config", Map.of("next", "end")),
                Map.of("key", "end", "type", "TERMINATE", "config", Map.of())));
        UUID definitionId = publish(owner, "wf-noname-" + UUID.randomUUID().toString().substring(0, 6),
                definition);

        WorkflowEngine.Run run = RequestContext.runAs(owner.tenantId(),
                () -> engine.start(owner.tenantId(), definitionId, "MANUAL", Map.of(), null, null, null,
                        null, owner.userId()));
        // start() only creates the run; the steps execute as it advances. advance() returns the step it just
        // ran, so the loop is fixed: the process has three steps.
        for (int i = 0; i < 5; i++) {
            try {
                RequestContext.runAs(owner.tenantId(), () -> engine.advance(owner.tenantId(), run.runId()));
            }
            catch (RuntimeException done) {
                // A finished run refuses to advance; that is the end of this test, not a failure.
                break;
            }
        }

        // A task called "task1" tells nobody anything, so the process name and the step key are used instead.
        String title = jdbc.queryForObject("select title from tasks where tenant_id = ? limit 1",
                String.class, owner.tenantId());
        assertThat(title).contains("make");
    }
}
