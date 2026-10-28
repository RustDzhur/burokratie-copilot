package de.buerokratiecopilot.workflow.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

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
 * Processes over HTTP (spec §16, §20; F-001 to F-005).
 *
 * <p>The acceptance criterion of F-004 is that a process assembled in the editor runs and is executed by the
 * engine. It was verified through the services, which is a true statement about the code — and this walks
 * the same path over HTTP, which is what makes it one about anybody being able to use it.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkflowControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    /** A process with a branch, in the shape a canvas sends. */
    private static final String GRAPH = """
            {"entryNode":"start",
             "nodes":[
               {"key":"start","type":"ACTION","config":{},"x":100,"y":100},
               {"key":"check","type":"CONDITION","config":{"field":"urgent","equals":"true"},
                "x":100,"y":210},
               {"key":"fast","type":"TERMINATE","config":{},"x":300,"y":320},
               {"key":"slow","type":"NOTIFICATION","config":{},"x":100,"y":320},
               {"key":"end","type":"TERMINATE","config":{},"x":100,"y":430}],
             "edges":[
               {"from":"start","to":"check","label":""},
               {"from":"check","to":"fast","label":"true"},
               {"from":"check","to":"slow","label":"false"},
               {"from":"slow","to":"end","label":""}]}
            """;

    /** The same process as a definition, which is what publishing takes. */
    private static final String DEFINITION = """
            {"entryStep":"end","steps":[{"key":"end","type":"TERMINATE","config":{}}]}
            """;

    private record Session(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private Session register() throws Exception {
        String email = "wf-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
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

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn();
        return new Session(login.getResponse().getCookie("bc_session"), tenantId, userId);
    }

    private String uniqueCode() {
        return "wf_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private UUID define(Session session) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/workflows").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","name":"Testprozess","category":"MANUAL"}
                                """.formatted(uniqueCode())))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(JsonPath.read(created.getResponse().getContentAsString(),
                "$.definitionId"));
    }

    // ---------------------------------------------------------------- the routes

    @Test
    @DisplayName("the palette is served where an editor expects it")
    void thePaletteIsServed() throws Exception {
        Session session = register();

        mockMvc.perform(get("/api/v1/workflows/palette").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").isNotEmpty())
                .andExpect(jsonPath("$[0].fields").isArray());
    }

    @Test
    @DisplayName("a process is registered and appears in the list")
    void aProcessIsRegistered() throws Exception {
        Session session = register();
        String code = uniqueCode();

        mockMvc.perform(post("/api/v1/workflows").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"%s\",\"name\":\"Testprozess\",\"category\":\"MANUAL\"}"
                                .formatted(code)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(code));

        mockMvc.perform(get("/api/v1/workflows").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").isNotEmpty());
    }

    @Test
    @DisplayName("the same code registers no second process")
    void theSameCodeRegistersOneProcess() throws Exception {
        Session session = register();
        String code = uniqueCode();
        mockMvc.perform(post("/api/v1/workflows").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"%s\",\"name\":\"Erster\",\"category\":\"MANUAL\"}"
                                .formatted(code)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/workflows").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"%s\",\"name\":\"Zweiter\",\"category\":\"MANUAL\"}"
                                .formatted(code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(false));
    }

    @Test
    @DisplayName("a definition that leads nowhere is refused with its problems")
    void anInvalidDefinitionIsRefused() throws Exception {
        Session session = register();
        UUID definitionId = define(session);

        mockMvc.perform(post("/api/v1/workflows/{id}/versions", definitionId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"definition":{"entryStep":"start","steps":[
                                  {"key":"start","type":"ACTION","config":{"next":"gibt-es-nicht"}}]}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(
                        "gibt-es-nicht")));
    }

    @Test
    @DisplayName("a valid definition is published as a version")
    void aValidDefinitionIsPublished() throws Exception {
        Session session = register();
        UUID definitionId = define(session);

        mockMvc.perform(post("/api/v1/workflows/{id}/versions", definitionId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"definition\":%s}".formatted(DEFINITION)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        mockMvc.perform(get("/api/v1/workflows/{id}/versions", definitionId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value(1));
    }

    // ---------------------------------------------------------------- the acceptance criterion

    @Test
    @DisplayName("a process assembled in the editor runs and is executed by the engine")
    void aProcessAssembledInTheEditorRuns() throws Exception {
        Session session = register();
        UUID definitionId = define(session);

        // The editor's own path: assemble, check, publish.
        mockMvc.perform(post("/api/v1/workflows/{id}/graph/check", definitionId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(GRAPH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));

        mockMvc.perform(post("/api/v1/workflows/{id}/graph/publish", definitionId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(GRAPH))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));

        // And the engine runs what came out.
        MvcResult started = mockMvc.perform(post("/api/v1/workflows/{id}/runs", definitionId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"triggerType":"MANUAL","triggerPayload":{"urgent":"true"},
                                 "idempotencyKey":"http-run-1"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        UUID runId = UUID.fromString(JsonPath.read(started.getResponse().getContentAsString(), "$.runId"));

        // start -> check -> fast, and the process is at its end.
        for (int step = 0; step < 3; step++) {
            mockMvc.perform(post("/api/v1/workflow-runs/{id}/advance", runId).with(csrf())
                            .cookie(session.cookie()))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/api/v1/workflow-runs/{id}", runId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.steps").isArray())
                .andExpect(jsonPath("$.events").isArray());
    }

    @Test
    @DisplayName("an assembled process that leads nowhere is refused before it is published")
    void anAssembledProcessThatLeadsNowhereIsRefused() throws Exception {
        Session session = register();
        UUID definitionId = define(session);

        mockMvc.perform(post("/api/v1/workflows/{id}/graph/publish", definitionId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entryNode":"start","nodes":[
                                  {"key":"start","type":"ACTION","config":{}}],
                                 "edges":[{"from":"start","to":"weg","label":""}]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a step nothing leads to is caught while the author is still looking at it")
    void anUnreachableStepIsCaught() throws Exception {
        Session session = register();
        UUID definitionId = define(session);

        mockMvc.perform(post("/api/v1/workflows/{id}/graph/check", definitionId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entryNode":"start","nodes":[
                                  {"key":"start","type":"TERMINATE","config":{}},
                                  {"key":"verloren","type":"ACTION","config":{}}],
                                 "edges":[]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.problems[0].code").value("UNREACHABLE"));
    }

    @Test
    @DisplayName("a saved process opens again as a picture")
    void aSavedProcessOpensAsAPicture() throws Exception {
        Session session = register();
        UUID definitionId = define(session);
        mockMvc.perform(post("/api/v1/workflows/{id}/graph/publish", definitionId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(GRAPH))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/workflows/{id}/graph", definitionId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entryNode").value("start"))
                .andExpect(jsonPath("$.nodes.length()").value(5))
                .andExpect(jsonPath("$.edges.length()").value(4));
    }

    // ---------------------------------------------------------------- runs and events

    @Test
    @DisplayName("starting with the same key twice produces one run")
    void startingTwiceProducesOneRun() throws Exception {
        Session session = register();
        UUID definitionId = define(session);
        mockMvc.perform(post("/api/v1/workflows/{id}/versions", definitionId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"definition\":%s}".formatted(DEFINITION)))
                .andExpect(status().isCreated());

        MvcResult first = mockMvc.perform(post("/api/v1/workflows/{id}/runs", definitionId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"same-http\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        MvcResult second = mockMvc.perform(post("/api/v1/workflows/{id}/runs", definitionId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"same-http\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String firstRunId = JsonPath.read(first.getResponse().getContentAsString(), "$.runId");
        String secondRunId = JsonPath.read(second.getResponse().getContentAsString(), "$.runId");
        org.assertj.core.api.Assertions.assertThat(secondRunId).isEqualTo(firstRunId);
    }

    @Test
    @DisplayName("advancing twice does not run the step twice")
    void advancingTwiceDoesNotRepeatTheStep() throws Exception {
        Session session = register();
        UUID definitionId = define(session);
        mockMvc.perform(post("/api/v1/workflows/{id}/versions", definitionId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"definition\":%s}".formatted(DEFINITION)))
                .andExpect(status().isCreated());
        MvcResult started = mockMvc.perform(post("/api/v1/workflows/{id}/runs", definitionId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"advance-http\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        UUID runId = UUID.fromString(JsonPath.read(started.getResponse().getContentAsString(), "$.runId"));

        mockMvc.perform(post("/api/v1/workflow-runs/{id}/advance", runId).with(csrf())
                        .cookie(session.cookie()))
                .andExpect(status().isOk());
        // The second call is exactly what a retry after a crash produces, and it must not repeat the work.
        mockMvc.perform(post("/api/v1/workflow-runs/{id}/advance", runId).with(csrf())
                        .cookie(session.cookie()))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("delivering an event nobody waits for changes nothing, and says so")
    void anEventNobodyWaitsForChangesNothing() throws Exception {
        Session session = register();
        UUID definitionId = define(session);
        mockMvc.perform(post("/api/v1/workflows/{id}/versions", definitionId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"definition\":%s}".formatted(DEFINITION)))
                .andExpect(status().isCreated());
        MvcResult started = mockMvc.perform(post("/api/v1/workflows/{id}/runs", definitionId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"event-http\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        UUID runId = UUID.fromString(JsonPath.read(started.getResponse().getContentAsString(), "$.runId"));

        // Queues repeat and webhooks retry, so a second delivery is expected rather than an error - and the
        // answer says which of the two happened instead of failing.
        mockMvc.perform(post("/api/v1/workflow-runs/{id}/events", runId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventName\":\"etwas.anderes\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivered").value(false))
                .andExpect(jsonPath("$.note").isNotEmpty());
    }

    @Test
    @DisplayName("the presets are offered and can be installed once")
    void thePresetsAreOffered() throws Exception {
        Session session = register();

        mockMvc.perform(get("/api/v1/workflows/presets").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));

        mockMvc.perform(post("/api/v1/workflows/presets/install").with(csrf())
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.installed.length()").value(3));

        mockMvc.perform(post("/api/v1/workflows/presets/install").with(csrf())
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.installed.length()").value(0))
                .andExpect(jsonPath("$.alreadyPresent.length()").value(3));
    }

    @Test
    @DisplayName("the statuses a run can be listed in are served")
    void theRunStatusesAreServed() throws Exception {
        Session session = register();

        mockMvc.perform(get("/api/v1/workflow-runs/statuses").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").isNotEmpty());
    }

    // ---------------------------------------------------------------- what is refused

    @Test
    @DisplayName("without a session nothing is read or started")
    void withoutASessionNothingHappens() throws Exception {
        mockMvc.perform(get("/api/v1/workflows")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/workflows/palette")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/workflows").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"x\",\"name\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/workflows/{id}/runs", UUID.randomUUID()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a process of another account is not reachable")
    void anotherAccountsProcessIsNotReachable() throws Exception {
        Session first = register();
        Session second = register();
        UUID definitionId = define(first);

        // An empty list, not a 404 - and that is the better answer here rather than a worse one: a refusal
        // that distinguishes "exists but is not yours" from "does not exist" tells one account that the
        // other has a process with that identifier, and the empty answer tells it nothing at all.
        mockMvc.perform(get("/api/v1/workflows/{id}/versions", definitionId).cookie(second.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        mockMvc.perform(get("/api/v1/workflows").cookie(second.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("the workflow permissions exist and are held, which is what makes these reachable")
    void theWorkflowPermissionsAreHeld() {
        Integer held = jdbc.queryForObject("""
                select count(*) from role_permissions rp
                join roles r on r.id = rp.role_id
                join permissions p on p.id = rp.permission_id
                where p.code in ('workflows.view','workflows.publish','workflows.execute')
                  and r.tenant_id is null
                """, Integer.class);

        org.assertj.core.api.Assertions.assertThat(held)
                .as("the workflow permissions must be granted to a system role, or these endpoints are dead")
                .isGreaterThan(0);
    }
}
