package de.buerokratiecopilot.tasks;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * Verifies the task model (spec §12.1).
 *
 * <p>The interesting part is not creating a task but what "done" means. The test drives a task through
 * a checklist and a completion criterion, and checks that the platform refuses to call it finished
 * while the criterion is unstated or a required item is open — because a status that means something
 * different to each person reading it is not a status.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TaskCrudIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    private record Session(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private Session register() throws Exception {
        String email = "task-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Aufgaben Test","accountType":"COMPANY"}
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

    private String createTask(Session session, String extraJson) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/tasks").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Widerspruch einlegen\"%s}".formatted(extraJson)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    }

    @Test
    @DisplayName("a new task carries its fields and starts without an assignee")
    void createsTaskWithFields() throws Exception {
        Session session = register();

        mockMvc.perform(post("/api/v1/tasks").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Widerspruch einlegen","priority":"HIGH",
                                 "completionCriteria":"Widerspruch ist bei der Behörde eingegangen",
                                 "tags":["frist"]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.assignmentPending").value(true))
                .andExpect(jsonPath("$.completionCriteria")
                        .value("Widerspruch ist bei der Behörde eingegangen"))
                .andExpect(jsonPath("$.tags").value(org.hamcrest.Matchers.hasItem("frist")));
    }

    @Test
    @DisplayName("an assignment takes the task out of the distribution queue and starts it")
    void assignmentStartsTheTask() throws Exception {
        Session session = register();
        String taskId = createTask(session, "");

        mockMvc.perform(post("/api/v1/tasks/{id}/assignees", taskId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"%s\",\"role\":\"ASSIGNEE\"}".formatted(session.userId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("ASSIGNEE"));

        mockMvc.perform(get("/api/v1/tasks/{id}", taskId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.assignmentPending").value(false))
                .andExpect(jsonPath("$.task.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.task.startedAt").isNotEmpty())
                .andExpect(jsonPath("$.assignees.length()").value(1));
    }

    @Test
    @DisplayName("a watcher does not take the task out of the distribution queue")
    void watcherDoesNotTakeResponsibility() throws Exception {
        Session session = register();
        String taskId = createTask(session, "");

        mockMvc.perform(post("/api/v1/tasks/{id}/assignees", taskId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"%s\",\"role\":\"WATCHER\"}".formatted(session.userId())))
                .andExpect(status().isCreated());

        // A watcher does not do the work, so the task must still be waiting for somebody.
        mockMvc.perform(get("/api/v1/tasks/{id}", taskId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.assignmentPending").value(true))
                .andExpect(jsonPath("$.task.status").value("NEW"));
    }

    @Test
    @DisplayName("a checklist item is added, ticked and unticked again")
    void checklistItemsCanBeCorrected() throws Exception {
        Session session = register();
        String taskId = createTask(session,
                ",\"completionCriteria\":\"Unterlagen sind vollständig\"");

        MvcResult item = mockMvc.perform(post("/api/v1/tasks/{id}/checklist", taskId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"Mietvertrag beilegen\",\"required\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.done").value(false))
                .andReturn();
        String itemId = JsonPath.read(item.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(post("/api/v1/tasks/checklist/{itemId}/tick", itemId)
                        .param("done", "true").with(csrf()).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.done").value(true));

        // A mistake has to be correctable, otherwise people tick things to get past the check.
        mockMvc.perform(post("/api/v1/tasks/checklist/{itemId}/tick", itemId)
                        .param("done", "false").with(csrf()).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.done").value(false));
    }

    @Test
    @DisplayName("a task without a completion criterion cannot be completed")
    void refusesCompletionWithoutCriteria() throws Exception {
        Session session = register();
        String taskId = createTask(session, "");

        mockMvc.perform(post("/api/v1/tasks/{id}/complete", taskId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("completion criterion")));
    }

    @Test
    @DisplayName("an open required checklist item blocks completion until it is acknowledged")
    void openRequiredItemBlocksCompletion() throws Exception {
        Session session = register();
        String taskId = createTask(session,
                ",\"completionCriteria\":\"Unterlagen sind vollständig\"");
        mockMvc.perform(post("/api/v1/tasks/{id}/checklist", taskId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"Mietvertrag beilegen\",\"required\":true}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/tasks/{id}/complete", taskId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict());

        // A bare acknowledgement is not a decision, exactly as in the case lifecycle.
        mockMvc.perform(post("/api/v1/tasks/{id}/complete", taskId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"acknowledgeOpenItems\":true}"))
                .andExpect(status().isBadRequest());

        MvcResult completed = mockMvc.perform(post("/api/v1/tasks/{id}/complete", taskId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"acknowledgeOpenItems":true,
                                 "note":"Mietvertrag liegt der Behörde schon vor"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.completedAt").isNotEmpty())
                .andReturn();

        assertThat(completed.getResponse().getContentAsString()).doesNotContain("openItems\":0");
    }

    @Test
    @DisplayName("a task requiring an approval cannot be completed before it is given")
    void approvalIsRequiredWhenConfigured() throws Exception {
        Session session = register();
        String taskId = createTask(session, """
                ,"completionCriteria":"Zahlung ist angewiesen","requiresApproval":true
                """);

        mockMvc.perform(post("/api/v1/tasks/{id}/complete", taskId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("approval")));

        mockMvc.perform(post("/api/v1/tasks/{id}/approve", taskId).with(csrf())
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvedAt").isNotEmpty());

        mockMvc.perform(post("/api/v1/tasks/{id}/complete", taskId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));
    }

    @Test
    @DisplayName("a blocked task without a reason is refused, because nobody could unblock it")
    void blockingNeedsAReason() throws Exception {
        Session session = register();
        String taskId = createTask(session, "");

        mockMvc.perform(post("/api/v1/tasks/{id}/status", taskId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"BLOCKED\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/tasks/{id}/status", taskId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"BLOCKED\",\"reason\":\"Warten auf die Behörde\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BLOCKED"))
                .andExpect(jsonPath("$.blockedReason").value("Warten auf die Behörde"));
    }

    @Test
    @DisplayName("comments are kept, and internal ones are separate from the rest")
    void commentsDistinguishInternal() throws Exception {
        Session session = register();
        String taskId = createTask(session, "");

        mockMvc.perform(post("/api/v1/tasks/{id}/comments", taskId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Der Mandant hat angerufen\",\"internal\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.internal").value(true));

        mockMvc.perform(post("/api/v1/tasks/{id}/comments", taskId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Wir bitten um Geduld\",\"internal\":false}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.internal").value(false));

        mockMvc.perform(get("/api/v1/tasks/{id}", taskId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comments.length()").value(2));

        // An empty comment is not a note.
        mockMvc.perform(post("/api/v1/tasks/{id}/comments", taskId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"   \",\"internal\":true}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a task without a title is refused")
    void refusesTaskWithoutTitle() throws Exception {
        Session session = register();

        mockMvc.perform(post("/api/v1/tasks").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }
}
