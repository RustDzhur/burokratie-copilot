package de.buerokratiecopilot.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * Verifies the assignment recommendation end to end (spec §12.2 acceptance).
 *
 * <p>The criterion is what happens when nobody fits: the task must stay in manual distribution rather
 * than being handed to somebody arbitrarily. The test therefore configures a rule nobody satisfies,
 * asks for a suggestion, and checks that the answer says so in words — and that the task is still in
 * the distribution queue afterwards.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AssignmentIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    private record Session(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private Session register() throws Exception {
        String email = "assign-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Zuweisung Test","accountType":"COMPANY"}
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

    private String createTask(Session session) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/tasks").with(csrf())
                        .cookie(session.cookie()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Widerspruch einlegen\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    }

    /** Writes the tenant's assignment rules, the way an administrator would through the settings. */
    private void configureRules(Session session, String json) {
        RequestContext.runAs(session.tenantId(), () -> jdbc.update("""
                insert into system_settings (id, tenant_id, setting_key, category, value, value_type,
                                             description, requires_verification, version, is_active)
                values (gen_random_uuid(), ?, 'assignment.rules', 'ASSIGNMENT', ?::jsonb, 'JSON',
                        '{"de":"Zuweisungsregeln des Mandanten"}'::jsonb, false, 1, true)
                """, session.tenantId(), json));
    }

    @Test
    @DisplayName("with no rules configured the platform says so instead of choosing somebody")
    void withoutRulesNobodyIsRecommended() throws Exception {
        Session session = register();
        String taskId = createTask(session);

        mockMvc.perform(get("/api/v1/assignment/tasks/{id}/suggestion", taskId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(false))
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.explanation")
                        .value(org.hamcrest.Matchers.containsString("manuellen Verteilung")));

        // The acceptance criterion: the task is still waiting for a person to distribute it.
        mockMvc.perform(get("/api/v1/tasks/assignment-queue").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + taskId + "')]").isNotEmpty());
    }

    @Test
    @DisplayName("a rule the tenant's people do not satisfy leaves the task unassigned")
    void unsatisfiableRuleLeavesTheTaskUnassigned() throws Exception {
        Session session = register();
        configureRules(session, """
                [{"roleCode":"LEGAL_ADVISOR","weight":0.8,"description":"Nur Rechtsberatung"}]
                """);
        String taskId = createTask(session);

        // The founder is an owner, not a legal advisor, so the rule matches nobody.
        mockMvc.perform(get("/api/v1/assignment/tasks/{id}/suggestion", taskId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(false))
                .andExpect(jsonPath("$.explanation")
                        .value(org.hamcrest.Matchers.containsString("Mindestbewertung")));
    }

    @Test
    @DisplayName("a rule the tenant's owner satisfies produces a recommendation with its basis")
    void satisfiedRuleRecommendsWithReasons() throws Exception {
        Session session = register();
        configureRules(session, """
                [{"roleCode":"OWNER","weight":0.8,"description":"Inhaber übernimmt"}]
                """);
        String taskId = createTask(session);

        mockMvc.perform(get("/api/v1/assignment/tasks/{id}/suggestion", taskId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.displayName").value("Zuweisung Test"))
                .andExpect(jsonPath("$.reasons[0]").value(org.hamcrest.Matchers.containsString("Inhaber")))
                .andExpect(jsonPath("$.score").isNumber());
    }

    @Test
    @DisplayName("the workload view counts open tasks per person")
    void workloadCountsOpenTasks() throws Exception {
        Session session = register();
        String taskId = createTask(session);
        mockMvc.perform(post("/api/v1/tasks/{id}/assignees", taskId).with(csrf())
                        .cookie(session.cookie()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"%s\",\"role\":\"ASSIGNEE\"}".formatted(session.userId())))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/assignment/workload").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.openTasks==1)]").isNotEmpty())
                .andExpect(jsonPath("$[0].relativeLoad").isNumber());
    }

    @Test
    @DisplayName("a rule set of the wrong shape is reported as no rules rather than assigning somebody")
    void brokenRulesFallBackToManualDistribution() throws Exception {
        Session session = register();
        // The column is jsonb, so the value is always valid JSON; what can be wrong is its shape. A
        // rule set that is not a list of rules is a configuration error, and the safe direction is
        // that a person decides rather than the platform guessing.
        configureRules(session, "{\"unexpected\": \"shape\"}");
        String taskId = createTask(session);

        // A configuration the platform cannot read must not lead to an assignment: the safe direction
        // is that a person decides.
        mockMvc.perform(get("/api/v1/assignment/tasks/{id}/suggestion", taskId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(false));

        assertThat(session.userId()).isNotNull();
    }
}
