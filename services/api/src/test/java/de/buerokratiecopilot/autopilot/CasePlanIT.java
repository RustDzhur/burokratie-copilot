package de.buerokratiecopilot.autopilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.http.Cookie;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import de.buerokratiecopilot.ai.AiProvider;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * CA-02 and CA-03: a plan is stored on the workflow engine's tables; a step does not start before what it depends on is done and
 * does not end without the evidence it demands; only what is safe runs by itself; a plan a model proposes is kept only when it
 * is valid.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@SuppressWarnings("unchecked")
class CasePlanIT {

    /** What the stand-in model answers when asked for a plan. */
    static final AtomicReference<String> ANSWER = new AtomicReference<>("");

    @TestConfiguration
    static class StubModel {
        @Bean
        AiProvider planStubProvider() {
            return new AiProvider() {
                @Override
                public String name() {
                    return "plan-stub";
                }

                @Override
                public Completion complete(Request request) {
                    return Completion.of(ANSWER.get(), 200, 80);
                }

                @Override
                public boolean isAvailable() {
                    return true;
                }
            };
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private record Account(UUID tenantId, UUID userId, Cookie cookie) {
    }

    private Account newTenant() throws Exception {
        String email = "plan-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Plan","accountType":"COMPANY"}
                                """.formatted(email)))
                .andExpect(status().isCreated()).andReturn();
        UUID tenantId = UUID.fromString(JsonPath.read(registration.getResponse().getContentAsString(), "$.tenantId"));
        UUID userId = UUID.fromString(JsonPath.read(registration.getResponse().getContentAsString(), "$.userId"));
        jdbc.update("update users set status = 'ACTIVE', email_verified_at = now() where id = ?", userId);
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"EinSicheresPasswort-2026!\"}".formatted(email)))
                .andExpect(status().isOk()).andReturn();
        return new Account(tenantId, userId, login.getResponse().getCookie("bc_session"));
    }

    private UUID newCase(Account a) {
        return RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject("""
                insert into cases (id, tenant_id, reference, title, status, priority, opened_at, owner_user_id, responsible_user_id, goal)
                values (gen_random_uuid(), ?, ?, 'Preiserhöhung widersprechen', 'IN_PROGRESS', 'NORMAL', now(), ?, ?, 'Erhöhung klären')
                returning id
                """, UUID.class, a.tenantId(), "P-" + UUID.randomUUID().toString().substring(0, 6), a.userId(), a.userId()));
    }

    private MvcResult send(Account a, MockHttpServletRequestBuilder builder, String content) throws Exception {
        return mockMvc.perform(builder.with(csrf()).cookie(a.cookie()).contentType(MediaType.APPLICATION_JSON).content(content)).andReturn();
    }

    private static final String PLAN = """
            {"steps":[
              {"key":"analyse","action":"ANALYZE_DOCUMENTS","title":"Dokumente auswerten"},
              {"key":"aufgabe","action":"CREATE_TASK","title":"Vertrag prüfen","dependsOn":["analyse"],"params":{"taskTitle":"Vertrag prüfen","dueInDays":5}},
              {"key":"entwurf","action":"DRAFT_LETTER","title":"Widerspruch entwerfen","dependsOn":["aufgabe"],"evidenceRequired":true},
              {"key":"freigabe","action":"REQUEST_APPROVAL","title":"Freigabe anfordern","dependsOn":["entwurf"]},
              {"key":"senden","action":"SEND_LETTER","title":"Widerspruch senden","dependsOn":["freigabe"],"evidenceRequired":true},
              {"key":"warten","action":"WAIT_FOR_REPLY","title":"Auf Antwort warten","dependsOn":["senden"]}
            ]}
            """;

    @Test
    @DisplayName("a step does not start before its dependencies are done; the platform runs only the safe steps itself; evidence is demanded")
    void stepsFollowDependencies() throws Exception {
        Account a = newTenant();
        UUID caseId = newCase(a);
        assertThat(send(a, put("/api/v1/cases/" + caseId + "/plan"), PLAN).getResponse().getStatus()).isEqualTo(200);

        String plan = mockMvc.perform(get("/api/v1/cases/" + caseId + "/plan").cookie(a.cookie())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((List<Object>) JsonPath.read(plan, "$.steps[*].step_key")).containsExactly("analyse", "aufgabe", "entwurf", "freigabe", "senden", "warten");
        assertThat((List<Object>) JsonPath.read(plan, "$.steps[?(@.ready == true)].step_key")).containsExactly("analyse");

        assertThat(send(a, post("/api/v1/cases/" + caseId + "/plan/steps/entwurf/start"), "{}").getResponse().getStatus())
                .as("the draft cannot begin before the task step is done").isEqualTo(409);

        String advanced = send(a, post("/api/v1/cases/" + caseId + "/plan/advance"), "{}").getResponse().getContentAsString();
        assertThat((Object) JsonPath.read(advanced, "$.ranAutomatically")).isEqualTo(2);
        assertThat(RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject(
                "select count(*) from tasks where case_id = ? and title = 'Vertrag prüfen' and source = 'WORKFLOW'", Integer.class, caseId))).isEqualTo(1);
        assertThat((List<Object>) JsonPath.read(advanced, "$.steps[?(@.ready == true)].step_key"))
                .as("what needs a person waits for a person").containsExactly("entwurf");

        // Running it again changes nothing.
        String again = send(a, post("/api/v1/cases/" + caseId + "/plan/advance"), "{}").getResponse().getContentAsString();
        assertThat((Object) JsonPath.read(again, "$.ranAutomatically")).isEqualTo(0);

        assertThat(send(a, post("/api/v1/cases/" + caseId + "/plan/steps/entwurf/start"), "{}").getResponse().getStatus()).isEqualTo(200);
        assertThat(send(a, post("/api/v1/cases/" + caseId + "/plan/steps/entwurf/complete"), "{}").getResponse().getStatus())
                .as("this step demands evidence").isEqualTo(400);
        assertThat(send(a, post("/api/v1/cases/" + caseId + "/plan/steps/entwurf/complete"),
                "{\"evidenceType\":\"NOTE\",\"note\":\"Entwurf liegt als Nachricht vor\"}").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("a failure is kept with its reason and the step can be tried again; leaving a step out needs a reason; the finished plan is completed")
    void failureAndRetry() throws Exception {
        Account a = newTenant();
        UUID caseId = newCase(a);
        send(a, put("/api/v1/cases/" + caseId + "/plan"), """
                {"steps":[{"key":"frage","action":"ASK_USER","title":"Unterlagen anfordern"},
                          {"key":"auswerten","action":"COMPARE_WITH_PREVIOUS","title":"Vergleichen","dependsOn":["frage"]}]}
                """);

        send(a, post("/api/v1/cases/" + caseId + "/plan/steps/frage/start"), "{}");
        assertThat(send(a, post("/api/v1/cases/" + caseId + "/plan/steps/frage/fail"), "{\"reason\":\"Kunde nicht erreichbar\"}").getResponse().getStatus())
                .isEqualTo(200);
        Object message = RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject(
                "select error_message from workflow_run_steps where step_key = 'frage' and tenant_id = ?", String.class, a.tenantId()));
        assertThat(message).isEqualTo("Kunde nicht erreichbar");

        assertThat(send(a, post("/api/v1/cases/" + caseId + "/plan/steps/frage/skip"), "{}").getResponse().getStatus())
                .as("skipping needs a reason").isEqualTo(400);
        assertThat(send(a, post("/api/v1/cases/" + caseId + "/plan/steps/frage/start"), "{}").getResponse().getStatus()).as("retry").isEqualTo(200);
        send(a, post("/api/v1/cases/" + caseId + "/plan/steps/frage/complete"), "{}");
        // The comparison only reads, so the platform runs it itself as soon as the question is answered.
        String done = send(a, post("/api/v1/cases/" + caseId + "/plan/advance"), "{}").getResponse().getContentAsString();
        assertThat((Object) JsonPath.read(done, "$.ranAutomatically")).isEqualTo(1);
        assertThat((Object) JsonPath.read(done, "$.run.status")).isEqualTo("COMPLETED");
        assertThat(RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject(
                "select attempt from workflow_run_steps where step_key = 'frage' and tenant_id = ?", Integer.class, a.tenantId()))).isEqualTo(2);
    }

    @Test
    @DisplayName("an invalid plan is not saved; a plan under way is not replaced; another workspace cannot see or change it")
    void rejectedReplacedIsolated() throws Exception {
        Account a = newTenant();
        Account b = newTenant();
        UUID caseId = newCase(a);

        MvcResult bad = send(a, put("/api/v1/cases/" + caseId + "/plan"),
                "{\"steps\":[{\"key\":\"senden\",\"action\":\"SEND_LETTER\",\"title\":\"Brief senden\"}]}");
        assertThat(bad.getResponse().getStatus()).isEqualTo(400);
        assertThat(bad.getResponse().getContentAsString()).contains("Freigabe");
        assertThat(RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject(
                "select count(*) from workflow_runs where case_id = ?", Integer.class, caseId))).as("nothing was saved").isZero();

        send(a, put("/api/v1/cases/" + caseId + "/plan"), "{\"steps\":[{\"key\":\"a\",\"action\":\"ASK_USER\",\"title\":\"Fragen\"}]}");
        assertThat(send(a, put("/api/v1/cases/" + caseId + "/plan"), "{\"steps\":[{\"key\":\"b\",\"action\":\"ASK_USER\",\"title\":\"Andere Frage\"}]}")
                .getResponse().getStatus()).as("an unstarted plan may be replaced").isEqualTo(200);
        send(a, post("/api/v1/cases/" + caseId + "/plan/steps/b/start"), "{}");
        assertThat(send(a, put("/api/v1/cases/" + caseId + "/plan"), "{\"steps\":[{\"key\":\"c\",\"action\":\"ASK_USER\",\"title\":\"Dritte\"}]}")
                .getResponse().getStatus()).as("a plan under way stays").isEqualTo(409);

        mockMvc.perform(get("/api/v1/cases/" + caseId + "/plan").cookie(b.cookie())).andExpect(status().isNotFound());
        assertThat(send(b, post("/api/v1/cases/" + caseId + "/plan/steps/b/complete"), "{}").getResponse().getStatus()).isEqualTo(404);
        assertThat(send(b, put("/api/v1/cases/" + caseId + "/plan"), "{\"steps\":[{\"key\":\"x\",\"action\":\"ASK_USER\",\"title\":\"x\"}]}")
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("a model's valid plan is saved; a plan that would send without approval, or that is not a plan, is dropped with the reasons")
    void proposalFromAModel() throws Exception {
        Account a = newTenant();
        UUID caseId = newCase(a);

        ANSWER.set("Hier ist der Plan: " + PLAN + " Viel Erfolg!");
        String saved = send(a, post("/api/v1/cases/" + caseId + "/plan/propose"), "{}").getResponse().getContentAsString();
        assertThat((Object) JsonPath.read(saved, "$.kind")).isEqualTo("SAVED");
        assertThat(RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject(
                "select context ->> 'source' from workflow_runs where case_id = ?", String.class, caseId))).isEqualTo("AI");

        Account other = newTenant();
        UUID otherCase = newCase(other);
        ANSWER.set("{\"steps\":[{\"key\":\"s\",\"action\":\"SEND_LETTER\",\"title\":\"Sofort senden\"},"
                + "{\"key\":\"t\",\"action\":\"RUN_SHELL\",\"title\":\"rm -rf\"}]}");
        String rejected = send(other, post("/api/v1/cases/" + otherCase + "/plan/propose"), "{}").getResponse().getContentAsString();
        assertThat((Object) JsonPath.read(rejected, "$.kind")).isEqualTo("REJECTED");
        assertThat(rejected).contains("UNKNOWN_ACTION");

        ANSWER.set("Ich kann dazu leider nichts sagen.");
        assertThat((Object) JsonPath.read(send(other, post("/api/v1/cases/" + otherCase + "/plan/propose"), "{}").getResponse().getContentAsString(),
                "$.kind")).isEqualTo("REJECTED");
        assertThat(RequestContext.runAs(other.tenantId(), () -> jdbc.queryForObject(
                "select count(*) from workflow_runs where case_id = ?", Integer.class, otherCase))).as("nothing of it was kept").isZero();
    }
}
