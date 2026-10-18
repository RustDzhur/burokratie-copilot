package de.buerokratiecopilot.ai.assistant.web;

import static org.assertj.core.api.Assertions.assertThat;
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

import de.buerokratiecopilot.ai.security.ToolPolicy;
import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * The assistant's tools are reachable, and a proposal only happens when somebody confirms it (spec §10.4).
 *
 * <p><strong>Why this test matters twice.</strong> {@code AssistantService} had two tests and no HTTP
 * surface, so every tool it owns was reachable only from a test. And {@code confirm} used to mark a proposal
 * done without running anything, so even a reachable confirmation would have been a success message about
 * work that did not happen. This walks the whole path over HTTP: propose, see that nothing exists, confirm,
 * see the task.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AssistantToolControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ToolPolicy policy;

    private record Session(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private Session register() throws Exception {
        String email = "tool-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Werkzeug Test","accountType":"COMPANY"}
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
        // A tool with an effect is off until the account switches it on.
        RequestContext.runAs(tenantId, () -> {
            policy.setToolEnabled(tenantId, "create_task", true, null);
            return null;
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

    private int taskCount(Session session) {
        Integer count = jdbc.queryForObject("select count(*) from tasks where tenant_id = ?",
                Integer.class, session.tenantId());
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("a tool with an effect comes back as a proposal and creates nothing yet")
    void aWriteToolIsProposedNotDone() throws Exception {
        Session session = register();

        MvcResult proposal = mockMvc.perform(post("/api/v1/assistant/tools").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tool":"create_task","parameters":{"title":"Rechnung prüfen"},
                                 "question":"Lege eine Aufgabe an"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.kind").value("PROPOSAL"))
                .andExpect(jsonPath("$.proposalId").isNotEmpty())
                .andReturn();

        assertThat(taskCount(session))
                .as("a proposal is a question, not an action")
                .isZero();
        assertThat(proposal.getResponse().getContentAsString()).contains("bestätigen");
    }

    @Test
    @DisplayName("confirming the proposal runs the tool and creates the task")
    void confirmingRunsTheTool() throws Exception {
        Session session = register();
        String proposalId = JsonPath.read(mockMvc.perform(post("/api/v1/assistant/tools").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tool":"create_task","parameters":{"title":"Rechnung prüfen"},
                                 "question":"Lege eine Aufgabe an"}
                                """))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString(), "$.proposalId");

        mockMvc.perform(post("/api/v1/assistant/proposals/{id}/confirm", proposalId).with(csrf())
                        .cookie(session.cookie()))
                .andExpect(status().isOk());

        assertThat(taskCount(session)).as("the confirmed action must have happened").isEqualTo(1);
        assertThat(jdbc.queryForObject("select title from tasks where tenant_id = ?",
                String.class, session.tenantId())).isEqualTo("Rechnung prüfen");
    }

    @Test
    @DisplayName("a read-only tool answers immediately and needs no confirmation")
    void aReadToolAnswersImmediately() throws Exception {
        Session session = register();

        mockMvc.perform(post("/api/v1/assistant/tools").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tool":"list_open_tasks","parameters":{},"question":"Was ist offen?"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("ANSWER"))
                .andExpect(jsonPath("$.result.tasks").isArray());
    }

    @Test
    @DisplayName("the proposals of a person are listed for them")
    void proposalsAreListed() throws Exception {
        Session session = register();
        mockMvc.perform(post("/api/v1/assistant/tools").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tool":"create_task","parameters":{"title":"Etwas"},
                                 "question":"Lege etwas an"}
                                """))
                .andExpect(status().isAccepted());

        mockMvc.perform(get("/api/v1/assistant/proposals").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    @DisplayName("without a session nothing is asked and nothing is confirmed")
    void withoutASessionNothingHappens() throws Exception {
        mockMvc.perform(post("/api/v1/assistant/tools").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tool\":\"list_open_tasks\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/assistant/proposals/{id}/confirm", UUID.randomUUID()).with(csrf()))
                .andExpect(status().isUnauthorized());
    }
}
