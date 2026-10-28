package de.buerokratiecopilot.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import de.buerokratiecopilot.cases.CaseTestSupport;
import de.buerokratiecopilot.identity.domain.Tenant;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.support.TenantContextExtension;
import de.buerokratiecopilot.support.TestDataFactory;

/**
 * Executable agent chains: the {@code AGENT} step, running a process to its end, and the three answers to
 * a pause.
 *
 * <p>The tests go through the real services against a real PostgreSQL database, because the properties
 * under test are database properties: the step record, the paused run, the retried attempt. A mocked engine
 * would prove that the methods are called, not that a constellation can be executed.</p>
 *
 * <p>Run with the dedicated database:
 * {@code BC_TEST_DB_NAME=burokratie_test_dag ./mvnw -q verify -Dit.test=WorkflowAgentStepIT}.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(TenantContextExtension.class)
class WorkflowAgentStepIT {

    @Autowired
    private WorkflowService workflows;

    @Autowired
    private WorkflowRunService runs;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestDataFactory factory;

    @Autowired
    private CaseTestSupport support;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper json;

    private record Account(UUID tenantId, UUID userId) {
    }

    private Account account() {
        String email = "dag-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        Tenant tenant = factory.createTenantWithOwner(email);
        User owner = factory.ownerOf(tenant);
        return new Account(tenant.getId(), owner.getId());
    }

    /** A two-step chain: {@code first} runs the named agent and leads to {@code second}. */
    private Map<String, Object> chain(String firstAgent, String onFailure,
                                      Map<String, Object> inputs) {
        Map<String, Object> firstConfig = new LinkedHashMap<>();
        firstConfig.put("agentId", firstAgent);
        firstConfig.put("next", "second");
        if (onFailure != null) {
            firstConfig.put("onFailure", onFailure);
        }
        if (inputs != null) {
            firstConfig.put("inputs", inputs);
        }
        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(Map.of("key", "first", "type", "AGENT", "config", firstConfig));
        steps.add(Map.of("key", "second", "type", "AGENT",
                "config", Map.of("agentId", "compliance-check")));
        return Map.of("entryStep", "first", "steps", steps);
    }

    private UUID publish(Account account, String code, Map<String, Object> definition) {
        return RequestContext.runAs(account.tenantId(), () -> {
            UUID definitionId = workflows.define(account.tenantId(), code, code, "Agentenkette",
                    "MANUAL", Map.of(), account.userId()).definitionId();
            workflows.publish(account.tenantId(), definitionId, definition, Map.of(), null,
                    account.userId());
            return definitionId;
        });
    }

    private WorkflowRunService.Outcome run(Account account, UUID definitionId, String key) {
        return RequestContext.runAs(account.tenantId(), () -> {
            RequestContext.setUserId(account.userId());
            return runs.run(account.tenantId(), definitionId, "MANUAL", Map.of(), null, null, null,
                    key, account.userId());
        });
    }

    private WorkflowRunService.Outcome resume(Account account, UUID runId, String decision,
                                              Map<String, Object> inputs) {
        return RequestContext.runAs(account.tenantId(), () -> {
            RequestContext.setUserId(account.userId());
            return runs.resume(account.tenantId(), runId, decision, inputs, account.userId());
        });
    }

    private String statusOf(UUID tenantId, UUID runId) {
        return jdbc.queryForObject("select status from workflow_runs where id = ? and tenant_id = ?",
                String.class, runId, tenantId);
    }

    private List<Map<String, Object>> stepsOf(UUID tenantId, UUID runId) {
        return jdbc.queryForList("""
                select step_key, status, attempt, input::text as input, output::text as output,
                       error_code, error_message
                from workflow_run_steps where run_id = ? and tenant_id = ?
                order by step_index
                """, runId, tenantId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> outputOf(Map<String, Object> step) {
        try {
            return json.readValue((String) step.get("output"), Map.class);
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Step output is not JSON: " + step.get("output"), ex);
        }
    }

    // ---------------------------------------------------------------- the chain

    @Test
    @DisplayName("a chain of two agents runs to its end and records both steps")
    void aChainOfTwoAgentsCompletes() {
        Account account = account();
        UUID definitionId = publish(account, "agentenkette", chain("compliance-check", null, null));

        WorkflowRunService.Outcome outcome = run(account, definitionId, "chain-1");

        assertThat(outcome.status()).as(outcome.asMap().toString()).isEqualTo("COMPLETED");
        assertThat(outcome.stepsExecuted()).isEqualTo(2);

        List<Map<String, Object>> steps = stepsOf(account.tenantId(), outcome.runId());
        assertThat(steps).hasSize(2);
        assertThat(steps).extracting(step -> step.get("step_key"))
                .containsExactly("first", "second");
        assertThat(steps).extracting(step -> step.get("status"))
                .containsOnly("SUCCEEDED");
        Map<String, Object> first = outputOf(steps.getFirst());
        assertThat(first.get("ok")).isEqualTo(true);
        assertThat(first.get("agentRunId")).isNotNull();
        assertThat((List<?>) first.get("produced")).isNotEmpty();
        assertThat(first.get("summary")).isNotNull();

        // Both agent runs are in the agent journal as well: the process history and the agent history agree.
        assertThat(jdbc.queryForObject("""
                select count(*) from agent_runs where tenant_id = ? and agent_id in
                    ('compliance-check','risk-flag') and trigger = 'workflow'
                """, Integer.class, account.tenantId())).isEqualTo(2);
    }

    @Test
    @DisplayName("the second step of a chain receives the next key, and the last one ends the process")
    void theChainFollowsItsNextKeys() {
        Account account = account();
        UUID definitionId = publish(account, "agentenkette_zwei", chain("risk-flag", null, null));

        WorkflowRunService.Outcome outcome = run(account, definitionId, "chain-2");

        assertThat(outcome.status()).isEqualTo("COMPLETED");
        // A completed run keeps the key of the last step it executed; the absence of a next step is what
        // completed it (the engine has always recorded the path, not cleared it).
        assertThat(outcome.currentStep()).isEqualTo("second");
        assertThat(stepsOf(account.tenantId(), outcome.runId()))
                .extracting(step -> step.get("step_key")).containsExactly("first", "second");
    }

    // ---------------------------------------------------------------- pausing

    @Test
    @DisplayName("an agent that refuses pauses the run, and APPROVE continues to the end")
    void aRefusingAgentPausesAndApproveCompletes() {
        Account account = account();
        // doc-extract refuses without a documentId: a real refusal, not a simulated one.
        UUID definitionId = publish(account, "agent_pause", chain("doc-extract", null, null));

        WorkflowRunService.Outcome paused = run(account, definitionId, "pause-1");

        assertThat(paused.status()).as(paused.asMap().toString())
                .isEqualTo("PAUSED_FOR_HUMAN_REVIEW");
        assertThat(paused.currentStep()).isEqualTo("first");
        assertThat(paused.stepsExecuted()).isEqualTo(1);
        assertThat(statusOf(account.tenantId(), paused.runId()))
                .isEqualTo("PAUSED_FOR_HUMAN_REVIEW");

        Map<String, Object> failed = stepsOf(account.tenantId(), paused.runId()).getFirst();
        assertThat(failed.get("status")).isEqualTo("FAILED");
        assertThat(failed.get("error_code")).isEqualTo("AGENT_FAILED");
        assertThat((String) failed.get("error_message")).contains("documentId");
        assertThat(outputOf(failed).get("ok")).isEqualTo(false);
        assertThat(jdbc.queryForObject("select failure_code from workflow_runs where id = ?",
                String.class, paused.runId())).isEqualTo("AGENT_FAILED");

        WorkflowRunService.Outcome resumed = resume(account, paused.runId(), "APPROVE", null);

        assertThat(resumed.status()).isEqualTo("COMPLETED");
        assertThat(resumed.stepsExecuted()).isEqualTo(1);
        List<Map<String, Object>> steps = stepsOf(account.tenantId(), resumed.runId());
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).get("status"))
                .as("the refused attempt stays visible after it was approved")
                .isEqualTo("FAILED");
        assertThat(steps.get(1).get("status")).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("SKIP writes the step down as skipped and continues")
    void skipContinuesAndMarksTheStep() {
        Account account = account();
        UUID definitionId = publish(account, "agent_skip", chain("doc-extract", null, null));
        WorkflowRunService.Outcome paused = run(account, definitionId, "skip-1");

        WorkflowRunService.Outcome resumed = resume(account, paused.runId(), "SKIP", null);

        assertThat(resumed.status()).isEqualTo("COMPLETED");
        List<Map<String, Object>> steps = stepsOf(account.tenantId(), resumed.runId());
        assertThat(steps.get(0).get("status")).isEqualTo("SKIPPED");
        assertThat(steps.get(1).get("status")).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("RETRY attempts the same step again and keeps the supplied inputs on the attempt")
    void retryRepeatsTheStepWithNewInputs() {
        Account account = account();
        UUID definitionId = publish(account, "agent_retry", chain("doc-extract", null, null));
        WorkflowRunService.Outcome paused = run(account, definitionId, "retry-1");
        UUID unknownDocument = UUID.randomUUID();

        WorkflowRunService.Outcome retried = resume(account, paused.runId(), "RETRY",
                Map.of("documentId", unknownDocument.toString()));

        // The document does not exist, so the agent refuses again — the point is that it ran again with the
        // supplied input, which is what RETRY promises.
        assertThat(retried.status()).isEqualTo("PAUSED_FOR_HUMAN_REVIEW");
        List<Map<String, Object>> attempts = stepsOf(account.tenantId(), retried.runId());
        assertThat(attempts).hasSize(2);
        assertThat(attempts).extracting(step -> step.get("step_key")).containsOnly("first");
        Map<String, Object> attemptTwo = jdbc.queryForMap("""
                select attempt, input::text as input, error_message from workflow_run_steps
                where run_id = ? and step_key = 'first' order by step_index desc limit 1
                """, retried.runId());
        assertThat(((Number) attemptTwo.get("attempt")).intValue()).isEqualTo(2);
        assertThat((String) attemptTwo.get("input")).contains(unknownDocument.toString());
        assertThat((String) attemptTwo.get("error_message"))
                .as("the agent really looked for the supplied document")
                .contains(unknownDocument.toString());

        assertThat(resume(account, retried.runId(), "APPROVE", null).status()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("onFailure=FAIL ends the run and a resume is refused")
    void onFailureFailEndsTheRun() {
        Account account = account();
        UUID definitionId = publish(account, "agent_fail", chain("doc-extract", "FAIL", null));

        WorkflowRunService.Outcome outcome = run(account, definitionId, "fail-1");

        assertThat(outcome.status()).isEqualTo("FAILED");
        assertThat(statusOf(account.tenantId(), outcome.runId())).isEqualTo("FAILED");
        assertThatThrownBy(() -> resume(account, outcome.runId(), "APPROVE", null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("menschliche Entscheidung");
    }

    @Test
    @DisplayName("onFailure=CONTINUE moves on, and the failed step still says FAILED")
    void onFailureContinueMovesOn() {
        Account account = account();
        UUID definitionId = publish(account, "agent_continue", chain("doc-extract", "CONTINUE", null));

        WorkflowRunService.Outcome outcome = run(account, definitionId, "continue-1");

        assertThat(outcome.status()).as(outcome.asMap().toString()).isEqualTo("COMPLETED");
        List<Map<String, Object>> steps = stepsOf(account.tenantId(), outcome.runId());
        assertThat(steps).extracting(step -> step.get("step_key"))
                .containsExactly("first", "second");
        assertThat(steps.get(0).get("status")).isEqualTo("FAILED");
        assertThat(steps.get(1).get("status")).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("an unknown decision on a paused run is refused without changing anything")
    void anUnknownDecisionIsRefused() {
        Account account = account();
        UUID definitionId = publish(account, "agent_decision", chain("doc-extract", null, null));
        WorkflowRunService.Outcome paused = run(account, definitionId, "decision-1");

        assertThatThrownBy(() -> resume(account, paused.runId(), "MAYBE", null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("APPROVE");
        assertThat(statusOf(account.tenantId(), paused.runId()))
                .isEqualTo("PAUSED_FOR_HUMAN_REVIEW");
    }

    // ---------------------------------------------------------------- validation

    @Test
    @DisplayName("publishing refuses an agent that is not in the catalogue")
    void publishingRefusesAnUnknownAgent() {
        Account account = account();
        UUID definitionId = RequestContext.runAs(account.tenantId(), () -> workflows.define(
                account.tenantId(), "agent_unbekannt", "Unbekannt", null, "MANUAL", Map.of(),
                account.userId()).definitionId());
        Map<String, Object> definition = Map.of("entryStep", "only",
                "steps", List.of(Map.of("key", "only", "type", "AGENT",
                        "config", Map.of("agentId", "gibt-es-nicht"))));

        assertThatThrownBy(() -> RequestContext.runAs(account.tenantId(), () -> workflows.publish(
                account.tenantId(), definitionId, definition, Map.of(), null, account.userId())))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("gibt-es-nicht");
    }

    @Test
    @DisplayName("publishing refuses an agent step without an agentId and an unknown onFailure")
    void publishingRefusesAnIncompleteAgentStep() {
        Account account = account();
        UUID definitionId = RequestContext.runAs(account.tenantId(), () -> workflows.define(
                account.tenantId(), "agent_unvollstaendig", "Unvollständig", null, "MANUAL", Map.of(),
                account.userId()).definitionId());
        Map<String, Object> definition = Map.of("entryStep", "only",
                "steps", List.of(Map.of("key", "only", "type", "AGENT",
                        "config", Map.of("onFailure", "IGNORE"))));

        assertThatThrownBy(() -> RequestContext.runAs(account.tenantId(), () -> workflows.publish(
                account.tenantId(), definitionId, definition, Map.of(), null, account.userId())))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("agentId")
                .hasMessageContaining("IGNORE");
    }

    // ---------------------------------------------------------------- HTTP

    private record Session(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private Session register() throws Exception {
        String email = "dag-http-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"DAG Test","accountType":"COMPANY"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID tenantId = UUID.fromString(JsonPath.read(
                registration.getResponse().getContentAsString(), "$.tenantId"));
        UUID userId = UUID.fromString(JsonPath.read(
                registration.getResponse().getContentAsString(), "$.userId"));
        jdbc.update("update users set status = 'ACTIVE', email_verified_at = now() where id = ?",
                userId);

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn();
        return new Session(login.getResponse().getCookie("bc_session"), tenantId, userId);
    }

    private String agentDefinition() {
        return """
                {"entryStep":"extract","steps":[
                  {"key":"extract","type":"AGENT",
                   "config":{"agentId":"doc-extract","onFailure":"PAUSE","next":"check"}},
                  {"key":"check","type":"AGENT","config":{"agentId":"compliance-check"}}]}
                """;
    }

    @Test
    @DisplayName("POST /workflows/{id}/run executes the chain; the run then pauses and resumes over HTTP")
    void runAndResumeOverHttp() throws Exception {
        Session session = register();
        String code = "http_dag_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult defined = mockMvc.perform(post("/api/v1/workflows").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"%s\",\"name\":\"DAG\",\"category\":\"MANUAL\"}"
                                .formatted(code)))
                .andExpect(status().isCreated())
                .andReturn();
        String definitionId = JsonPath.read(defined.getResponse().getContentAsString(),
                "$.definitionId");
        mockMvc.perform(post("/api/v1/workflows/{id}/versions", definitionId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"definition\":%s}".formatted(agentDefinition())))
                .andExpect(status().isCreated());

        MvcResult started = mockMvc.perform(post("/api/v1/workflows/{id}/run", definitionId)
                        .with(csrf()).cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"triggerType\":\"MANUAL\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PAUSED_FOR_HUMAN_REVIEW"))
                .andExpect(jsonPath("$.currentStep").value("extract"))
                .andExpect(jsonPath("$.stepsExecuted").value(1))
                .andReturn();
        String runId = JsonPath.read(started.getResponse().getContentAsString(), "$.runId");

        mockMvc.perform(get("/api/v1/workflow-runs/{id}", runId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED_FOR_HUMAN_REVIEW"))
                .andExpect(jsonPath("$.steps.length()").value(1))
                .andExpect(jsonPath("$.steps[0].ok").value(false))
                .andExpect(jsonPath("$.steps[0].output.ok").value(false))
                .andExpect(jsonPath("$.steps[0].agentRunId").isNotEmpty());

        mockMvc.perform(post("/api/v1/workflow-runs/{id}/resume", runId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.stepsExecuted").value(1));

        mockMvc.perform(get("/api/v1/workflow-runs/{id}", runId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.steps.length()").value(2));
    }

    @Test
    @DisplayName("an employee without workflows.run cannot start a process")
    void anEmployeeCannotRunAWorkflow() throws Exception {
        Session owner = register();
        String email = "dag-kollege-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        support.colleagueIn(owner.tenantId(), owner.userId(), email);
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn();
        Cookie employee = login.getResponse().getCookie("bc_session");

        String code = "http_deny_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult defined = mockMvc.perform(post("/api/v1/workflows").with(csrf())
                        .cookie(owner.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"%s\",\"name\":\"DAG\",\"category\":\"MANUAL\"}"
                                .formatted(code)))
                .andExpect(status().isCreated())
                .andReturn();
        String definitionId = JsonPath.read(defined.getResponse().getContentAsString(),
                "$.definitionId");
        mockMvc.perform(post("/api/v1/workflows/{id}/versions", definitionId).with(csrf())
                        .cookie(owner.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"definition\":%s}".formatted(agentDefinition())))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/workflows/{id}/run", definitionId).with(csrf())
                        .cookie(employee))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("instantiate saves datev-pipeline as six AGENT steps")
    void instantiateSavesDatevPipeline() throws Exception {
        Session session = register();

        MvcResult instantiated = mockMvc.perform(post("/api/v1/constellations/datev-pipeline/instantiate")
                        .with(csrf()).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(true))
                .andExpect(jsonPath("$.workflowId").isNotEmpty())
                .andExpect(jsonPath("$.stepCount").value(6))
                .andExpect(jsonPath("$.steps.length()").value(6))
                .andExpect(jsonPath("$.steps[0].agentId").value("doc-ocr"))
                .andExpect(jsonPath("$.steps[5].agentId").value("doc-datev"))
                .andReturn();

        String workflowId = JsonPath.read(instantiated.getResponse().getContentAsString(),
                "$.workflowId");
        // The saved version really is a chain of six agent calls, not just a response body. The definition
        // tables are under strict row-level security, so the read needs the tenant scope the request had.
        String definition = RequestContext.runAs(session.tenantId(), () -> jdbc.queryForObject("""
                select v.definition::text from workflow_versions v
                  join workflow_definitions d on d.id = v.definition_id
                 where d.id = ? and v.version = d.current_version
                """, String.class, UUID.fromString(workflowId)));
        assertThat(definition).contains("\"type\": \"AGENT\"");
        assertThat(definition.split("\"type\": \"AGENT\"", -1).length - 1).isEqualTo(6);
        assertThat(definition).contains("\"agentId\": \"doc-ocr\"");
        assertThat(definition).contains("\"agentId\": \"doc-datev\"");
    }

    @Test
    @DisplayName("instantiating a constellation the catalogue does not know is a 404")
    void instantiateUnknownConstellationIsNotFound() throws Exception {
        Session session = register();

        mockMvc.perform(post("/api/v1/constellations/gibt-es-nicht/instantiate").with(csrf())
                        .cookie(session.cookie()))
                .andExpect(status().isNotFound());
    }
}
