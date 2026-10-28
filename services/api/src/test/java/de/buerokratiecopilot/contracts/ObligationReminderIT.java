package de.buerokratiecopilot.contracts;

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
 * Verifies the obligation calendar and the reminders it produces (spec §13.1, D-002 acceptance).
 *
 * <p>The acceptance criterion is that a reminder is created by a rule. The test drives the window:
 * nothing happens before it, a task appears inside it, the named person is responsible, and an
 * obligation nobody is responsible for becomes work waiting to be distributed rather than a task
 * handed to somebody at random.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ObligationReminderIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    private record Session(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private Session register() throws Exception {
        String email = "obligation-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Pflichten Test","accountType":"COMPANY"}
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

    private String createContract(Session session) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/contracts").with(csrf())
                        .cookie(session.cookie()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Wartungsvertrag","contractNumber":"WV-%s"}
                                """.formatted(UUID.randomUUID().toString().substring(0, 6))))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    }

    private String addObligation(Session session, String contractId, String dueDate,
                                 Integer reminderDays, String responsibleUserId) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/contracts/{id}/obligations", contractId)
                        .with(csrf()).cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"obligationType":"PAYMENT","title":"Wartungspauschale zahlen",
                                 "dueDate":"%s","recurrence":"MONTHLY","amount":120.00,"currency":"EUR",
                                 "reminderDaysBefore":%s%s}
                                """.formatted(dueDate, reminderDays,
                                responsibleUserId == null ? ""
                                        : ",\"responsibleUserId\":\"" + responsibleUserId + "\"")))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    }

    private org.springframework.test.web.servlet.ResultActions runReminders(Session session, String today)
            throws Exception {
        return mockMvc.perform(post("/api/v1/contracts/obligations/run-reminders")
                .param("today", today).with(csrf()).cookie(session.cookie()));
    }

    @Test
    @DisplayName("nothing is reminded before the window is reached")
    void nothingHappensBeforeTheWindow() throws Exception {
        Session session = register();
        String contractId = createContract(session);
        addObligation(session, contractId, "2026-06-30", 14, session.userId().toString());

        runReminders(session, "2026-05-01")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(0));

        assertThat(session.tenantId()).isNotNull();
    }

    @Test
    @DisplayName("inside the window a reminder task is created for the responsible person")
    void createsReminderInsideTheWindow() throws Exception {
        Session session = register();
        String contractId = createContract(session);
        addObligation(session, contractId, "2026-06-30", 14, session.userId().toString());

        runReminders(session, "2026-06-20")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1));

        // The reminder is a real task, so it appears in the task list with a due date and a criterion.
        mockMvc.perform(get("/api/v1/tasks").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.title=='Vertragspflicht: Wartungspauschale zahlen')]")
                        .isNotEmpty())
                .andExpect(jsonPath("$.content[?(@.assignmentPending==false)]").isNotEmpty());
    }

    @Test
    @DisplayName("an obligation nobody is responsible for waits for distribution instead of being assigned")
    void unnamedResponsibleGoesToTheQueue() throws Exception {
        Session session = register();
        String contractId = createContract(session);
        addObligation(session, contractId, "2026-06-30", 14, null);

        // A reminder is still created, because the date matters; who does it does not, and that is a
        // question for a person.
        runReminders(session, "2026-06-20")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.skippedDetails[0]")
                        .value(org.hamcrest.Matchers.containsString("awaits distribution")));

        mockMvc.perform(get("/api/v1/tasks/assignment-queue").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.title=='Vertragspflicht: Wartungspauschale zahlen')]")
                        .isNotEmpty());
    }

    @Test
    @DisplayName("a passed due date becomes missed, not reminded")
    void passedDateIsMarkedMissed() throws Exception {
        Session session = register();
        String contractId = createContract(session);
        String obligationId = addObligation(session, contractId, "2026-05-01", 14,
                session.userId().toString());

        runReminders(session, "2026-06-20")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.skippedDetails[0]")
                        .value(org.hamcrest.Matchers.containsString("marked missed")));

        mockMvc.perform(get("/api/v1/contracts/{id}/obligations", contractId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + obligationId + "')].status")
                        .value(org.hamcrest.Matchers.contains("MISSED")));
    }

    @Test
    @DisplayName("an obligation without a warning period produces no reminder, because nobody set one")
    void noReminderWithoutAWindow() throws Exception {
        Session session = register();
        String contractId = createContract(session);

        // The API accepts a missing window; the platform does not invent one.
        mockMvc.perform(post("/api/v1/contracts/{id}/obligations", contractId).with(csrf())
                        .cookie(session.cookie()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"obligationType":"REPORT","title":"Jahresbericht liefern",
                                 "dueDate":"2026-06-30","recurrence":"YEARLY"}
                                """))
                .andExpect(status().isCreated());

        runReminders(session, "2026-06-29")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(0));
    }

    @Test
    @DisplayName("a fulfilled obligation is not reminded again")
    void fulfilledIsNotReminded() throws Exception {
        Session session = register();
        String contractId = createContract(session);
        String obligationId = addObligation(session, contractId, "2026-06-30", 14,
                session.userId().toString());

        mockMvc.perform(post("/api/v1/contracts/obligations/{id}/fulfil", obligationId).with(csrf())
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FULFILLED"));

        runReminders(session, "2026-06-20")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(0));
    }

    @Test
    @DisplayName("the obligation calendar shows what is due in a window")
    void calendarShowsWhatIsDue() throws Exception {
        Session session = register();
        String contractId = createContract(session);
        addObligation(session, contractId, "2026-06-30", 14, session.userId().toString());

        mockMvc.perform(get("/api/v1/contracts/obligations/calendar")
                        .param("from", "2026-06-01").param("to", "2026-07-31").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Wartungspauschale zahlen"));

        mockMvc.perform(get("/api/v1/contracts/obligations/calendar")
                        .param("from", "2026-08-01").param("to", "2026-08-31").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
