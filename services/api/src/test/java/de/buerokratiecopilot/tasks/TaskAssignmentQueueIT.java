package de.buerokratiecopilot.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import de.buerokratiecopilot.cases.CaseTestSupport;
import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * Verifies the manual distribution queue (spec §12.2 acceptance).
 *
 * <p>The criterion is that a task without an assignee lands in the queue. The test therefore checks
 * both directions: work nobody owns is listed and stays listed, and work that gets an owner leaves —
 * with a watcher explicitly not counting as an owner, because a task can look assigned while still
 * belonging to nobody.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TaskAssignmentQueueIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private CaseTestSupport support;

    private record Session(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private Session register() throws Exception {
        String email = "queue-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Verteilung Test","accountType":"COMPANY"}
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

    /** A colleague with the ordinary employee role, who may not distribute work. */
    private Session colleague(Session owner) throws Exception {
        String email = "kollege-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        UUID colleagueId = support.colleagueIn(owner.tenantId(), owner.userId(), email);

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn();
        return new Session(login.getResponse().getCookie("bc_session"), owner.tenantId(), colleagueId);
    }

    private String createTask(Session session, String title, String dueAt) throws Exception {
        String due = dueAt == null ? "" : ",\"dueAt\":\"%s\"".formatted(dueAt);
        MvcResult created = mockMvc.perform(post("/api/v1/tasks").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"%s\"%s}".formatted(title, due)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    }

    @Test
    @DisplayName("a task without an assignee is in the distribution queue")
    void unassignedTaskLandsInTheQueue() throws Exception {
        Session owner = register();
        String taskId = createTask(owner, "Widerspruch einlegen", null);

        mockMvc.perform(get("/api/v1/tasks/assignment-queue").cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + taskId + "')]").isNotEmpty());

        mockMvc.perform(get("/api/v1/tasks/statistics").cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.awaitingAssignment").value(1));
    }

    @Test
    @DisplayName("the queue is ordered by due date, so the most urgent work is distributed first")
    void queueIsOrderedByDueDate() throws Exception {
        Session owner = register();
        String later = createTask(owner, "Später fällig", "2027-01-01T09:00:00Z");
        String sooner = createTask(owner, "Bald fällig", "2026-06-01T09:00:00Z");
        String undated = createTask(owner, "Ohne Frist", null);

        MvcResult queue = mockMvc.perform(get("/api/v1/tasks/assignment-queue").cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andReturn();

        String body = queue.getResponse().getContentAsString();
        assertThat(body.indexOf(sooner)).as("the nearest deadline comes first: %s", body)
                .isLessThan(body.indexOf(later));
        assertThat(body.indexOf(later)).as("a task without a deadline comes last: %s", body)
                .isLessThan(body.indexOf(undated));
    }

    @Test
    @DisplayName("assigning the task as a doer removes it from the queue")
    void assigningRemovesFromQueue() throws Exception {
        Session owner = register();
        Session employee = colleague(owner);
        String taskId = createTask(owner, "Unterlagen anfordern", null);

        mockMvc.perform(post("/api/v1/tasks/{id}/assignees", taskId).with(csrf())
                        .cookie(owner.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"%s\",\"role\":\"ASSIGNEE\"}"
                                .formatted(employee.userId())))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/tasks/assignment-queue").cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + taskId + "')]").isEmpty());
    }

    @Test
    @DisplayName("removing the last doer returns the task to the queue")
    void removingTheDoerReturnsItToTheQueue() throws Exception {
        Session owner = register();
        Session employee = colleague(owner);
        String taskId = createTask(owner, "Unterlagen anfordern", null);
        mockMvc.perform(post("/api/v1/tasks/{id}/assignees", taskId).with(csrf())
                        .cookie(owner.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"%s\",\"role\":\"ASSIGNEE\"}"
                                .formatted(employee.userId())))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/api/v1/tasks/{id}/assignees/{userId}", taskId, employee.userId())
                        .with(csrf()).cookie(owner.cookie()))
                .andExpect(status().isNoContent());

        // Work must not silently become nobody's again.
        mockMvc.perform(get("/api/v1/tasks/assignment-queue").cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + taskId + "')]").isNotEmpty());
    }

    @Test
    @DisplayName("a colleague without the distribution permission cannot see the queue")
    void queueIsForThoseWhoDistributeWork() throws Exception {
        Session owner = register();
        Session employee = colleague(owner);
        createTask(owner, "Widerspruch einlegen", null);

        mockMvc.perform(get("/api/v1/tasks/assignment-queue").cookie(employee.cookie()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a completed task is not waiting to be distributed")
    void completedTaskLeavesTheQueue() throws Exception {
        Session owner = register();
        String taskId = createTask(owner, "Erledigt ohne Verteiler", null);
        mockMvc.perform(post("/api/v1/tasks/{id}/status", taskId).with(csrf())
                        .cookie(owner.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CANCELLED\",\"reason\":\"Nicht mehr erforderlich\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/tasks/assignment-queue").cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + taskId + "')]").isEmpty());
    }
}
