package de.buerokratiecopilot.cases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * CA-01 and CA-05: a case has a goal and checkable criteria; it cannot be completed while one is open, sending is not
 * delivering is not receiving an answer, and giving a criterion up is a decision with a reason.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@SuppressWarnings("unchecked")
class CaseCompletionIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private record Account(UUID tenantId, UUID userId, Cookie cookie) {
    }

    private Account newTenant() throws Exception {
        String email = "crit-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Criteria","accountType":"COMPANY"}
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
                insert into cases (id, tenant_id, reference, title, status, priority, opened_at, owner_user_id)
                values (gen_random_uuid(), ?, ?, 'Preiserhöhung klären', 'IN_PROGRESS', 'NORMAL', now(), ?) returning id
                """, UUID.class, a.tenantId(), "K-" + UUID.randomUUID().toString().substring(0, 6), a.userId()));
    }

    private String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private MvcResult json(Account a, MockHttpServletRequestBuilder builder, String content) throws Exception {
        return mockMvc.perform(builder.with(csrf()).cookie(a.cookie()).contentType(MediaType.APPLICATION_JSON).content(content)).andReturn();
    }

    @Test
    @DisplayName("a goal and criteria are set and shown; a done task meets its criterion by itself, a reply cannot be met by a done task")
    void goalAndCriteria() throws Exception {
        Account a = newTenant();
        UUID caseId = newCase(a);
        UUID task = RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject(
                "insert into tasks (id, tenant_id, case_id, title) values (gen_random_uuid(), ?, ?, 'Widerspruch senden') returning id",
                UUID.class, a.tenantId(), caseId));

        assertThat(json(a, put("/api/v1/cases/" + caseId + "/goal"),
                "{\"goal\":\"Erhöhung prüfen und widersprechen oder bestätigen\",\"nextAction\":\"Widerspruch senden\"}")
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(json(a, post("/api/v1/cases/" + caseId + "/criteria"),
                "{\"kind\":\"TASK_DONE\",\"description\":\"Widerspruch ist versendet\",\"taskId\":\"%s\"}".formatted(task))
                .getResponse().getStatus()).isEqualTo(201);
        assertThat(json(a, post("/api/v1/cases/" + caseId + "/criteria"),
                "{\"kind\":\"REPLY_RECEIVED\",\"description\":\"Antwort des Anbieters liegt vor\"}").getResponse().getStatus()).isEqualTo(201);
        assertThat(json(a, post("/api/v1/cases/" + caseId + "/criteria"),
                "{\"kind\":\"TASK_DONE\",\"description\":\"ohne Aufgabe\"}").getResponse().getStatus())
                .as("a criterion that is checked from a task must name the task").isEqualTo(400);

        String before = body(mockMvc.perform(get("/api/v1/cases/" + caseId + "/workspace").cookie(a.cookie())).andExpect(status().isOk()).andReturn());
        assertThat((Object) JsonPath.read(before, "$.goal")).isEqualTo("Erhöhung prüfen und widersprechen oder bestätigen");
        assertThat((List<Object>) JsonPath.read(before, "$.criteria[*].status")).containsExactly("OPEN", "OPEN");

        RequestContext.runAs(a.tenantId(), () -> jdbc.update("update tasks set status = 'DONE', completed_at = now() where id = ?", task));
        String after = body(mockMvc.perform(get("/api/v1/cases/" + caseId + "/workspace").cookie(a.cookie())).andExpect(status().isOk()).andReturn());
        assertThat((List<Object>) JsonPath.read(after, "$.criteria[*].status"))
                .as("sending the letter is not the same as receiving the answer").containsExactly("MET", "OPEN");
        assertThat((Object) JsonPath.read(after, "$.criteria[0].evidence_hash")).isNotNull();
    }

    @Test
    @DisplayName("the case cannot be completed while a criterion is open; it can be closed deliberately, but never with the reason 'outcome achieved'")
    void completionNeedsCriteria() throws Exception {
        Account a = newTenant();
        UUID caseId = newCase(a);
        json(a, post("/api/v1/cases/" + caseId + "/criteria"), "{\"kind\":\"DECISION\",\"description\":\"Entscheidung über den Vertrag ist getroffen\"}");

        assertThat(json(a, post("/api/v1/cases/" + caseId + "/status"), "{\"status\":\"COMPLETED\"}").getResponse().getStatus()).isEqualTo(409);
        assertThat(json(a, post("/api/v1/cases/" + caseId + "/status"),
                "{\"status\":\"CLOSED\",\"closeReason\":\"OUTCOME_ACHIEVED\"}").getResponse().getStatus()).isEqualTo(409);
        assertThat(json(a, post("/api/v1/cases/" + caseId + "/status"),
                "{\"status\":\"CLOSED\",\"closeReason\":\"USER_DECISION\"}").getResponse().getStatus())
                .as("closing with an open criterion needs an explicit acknowledgement").isEqualTo(409);
        assertThat(json(a, post("/api/v1/cases/" + caseId + "/status"),
                "{\"status\":\"CLOSED\",\"closeReason\":\"USER_DECISION\",\"acknowledgeOpenWork\":true,\"note\":\"Anbieter hat sich telefonisch erledigt\"}")
                .getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("a criterion is met by a person with evidence, or given up with a reason; then the case can be completed")
    void metOrWaived() throws Exception {
        Account a = newTenant();
        UUID caseId = newCase(a);
        String created = body(json(a, post("/api/v1/cases/" + caseId + "/criteria"),
                "{\"kind\":\"DECISION\",\"description\":\"Entscheidung ist getroffen\"}"));
        String decision = JsonPath.read(created, "$.criteria[0].id");
        String second = body(json(a, post("/api/v1/cases/" + caseId + "/criteria"),
                "{\"kind\":\"CUSTOM\",\"description\":\"Kopie ist abgelegt\"}"));
        String custom = JsonPath.read(second, "$.criteria[1].id");

        assertThat(json(a, post("/api/v1/cases/" + caseId + "/criteria/" + decision + "/met"), "{}").getResponse().getStatus())
                .as("without evidence nothing is met").isEqualTo(400);
        assertThat(json(a, post("/api/v1/cases/" + caseId + "/criteria/" + decision + "/met"),
                "{\"evidenceType\":\"NOTE\",\"note\":\"Telefonat am 14.10.: Vertrag bleibt\"}").getResponse().getStatus()).isEqualTo(200);
        assertThat(json(a, post("/api/v1/cases/" + caseId + "/criteria/" + custom + "/waive"), "{\"reason\":\" \"}").getResponse().getStatus())
                .as("giving up needs a reason").isEqualTo(400);
        assertThat(json(a, post("/api/v1/cases/" + caseId + "/criteria/" + custom + "/waive"),
                "{\"reason\":\"Kopie ist nicht nötig\"}").getResponse().getStatus()).isEqualTo(200);

        Map<String, Object> row = RequestContext.runAs(a.tenantId(), () -> jdbc.queryForMap(
                "select status, evidence_hash from case_completion_criteria where id = ?::uuid", decision));
        assertThat(row.get("status")).isEqualTo("MET");
        assertThat((String) row.get("evidence_hash")).hasSize(64);
        assertThat(json(a, post("/api/v1/cases/" + caseId + "/status"), "{\"status\":\"COMPLETED\"}").getResponse().getStatus()).isEqualTo(200);
        assertThat(RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject(
                "select count(*) from case_events where case_id = ? and event_type in ('CRITERION_MET', 'CRITERION_WAIVED', 'CRITERION_ADDED')",
                Integer.class, caseId))).isEqualTo(4);
    }

    @Test
    @DisplayName("a letter counts as delivered only when the provider confirmed delivery; sending alone does not meet the criterion")
    void sentIsNotDelivered() throws Exception {
        Account a = newTenant();
        UUID caseId = newCase(a);
        UUID message = RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject(
                "insert into outbound_messages (id, tenant_id, case_id) values (gen_random_uuid(), ?, ?) returning id", UUID.class,
                a.tenantId(), caseId));
        json(a, post("/api/v1/cases/" + caseId + "/criteria"),
                "{\"kind\":\"MESSAGE_DELIVERED\",\"description\":\"Widerspruch wurde zugestellt\",\"outboundMessageId\":\"%s\"}".formatted(message));

        RequestContext.runAs(a.tenantId(), () -> jdbc.update("""
                insert into message_delivery_events (outbound_message_id, tenant_id, event_type, proves_delivery)
                values (?, ?, 'ACCEPTED_BY_PROVIDER', false)
                """, message, a.tenantId()));
        String sent = body(mockMvc.perform(get("/api/v1/cases/" + caseId + "/workspace").cookie(a.cookie())).andExpect(status().isOk()).andReturn());
        assertThat((Object) JsonPath.read(sent, "$.criteria[0].status")).isEqualTo("OPEN");

        RequestContext.runAs(a.tenantId(), () -> jdbc.update("""
                insert into message_delivery_events (outbound_message_id, tenant_id, event_type, proves_delivery)
                values (?, ?, 'DELIVERED', true)
                """, message, a.tenantId()));
        String delivered = body(mockMvc.perform(get("/api/v1/cases/" + caseId + "/workspace").cookie(a.cookie())).andExpect(status().isOk()).andReturn());
        assertThat((Object) JsonPath.read(delivered, "$.criteria[0].status")).isEqualTo("MET");
    }

    @Test
    @DisplayName("another workspace cannot see or change the goal and criteria of a case")
    void isolation() throws Exception {
        Account a = newTenant();
        Account b = newTenant();
        UUID caseId = newCase(a);

        mockMvc.perform(get("/api/v1/cases/" + caseId + "/workspace").cookie(b.cookie())).andExpect(status().isNotFound());
        assertThat(json(b, put("/api/v1/cases/" + caseId + "/goal"), "{\"goal\":\"x\"}").getResponse().getStatus()).isEqualTo(404);
        assertThat(json(b, post("/api/v1/cases/" + caseId + "/criteria"), "{\"kind\":\"CUSTOM\",\"description\":\"x\"}").getResponse().getStatus())
                .isEqualTo(404);
    }
}
