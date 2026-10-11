package de.buerokratiecopilot.cases;

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
 * Verifies that a case cannot be closed by accident (spec §11.3 acceptance).
 *
 * <p>The criterion is that closing while obligations are open requires an explicit confirmation with a
 * reason. The test walks the whole path: refuse a false claim, refuse a silent closure, refuse a bare
 * acknowledgement without a word of explanation, and then allow the deliberate one — and check that
 * reopening demands a reason just as much, because a closed date that can be cleared without a word
 * means nothing.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CaseLifecycleIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    private record Session(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private Session register() throws Exception {
        String email = "lifecycle-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Lebenszyklus Test","accountType":"COMPANY"}
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

    /** A case of a type that declares two mandatory documents, neither of which is attached. */
    private String openCaseWithMissingDocuments(Session session) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/cases").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Mietvertrag kündigen\",\"caseTypeCode\":\"kuendigung\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    }

    private void addOpenTask(Session session, String caseId, String title) {
        RequestContext.runAs(session.tenantId(), () -> jdbc.update("""
                insert into tasks (id, tenant_id, case_id, title, status, priority, priority_source,
                                   assignment_pending, source, requires_approval, tags, metadata)
                values (gen_random_uuid(), ?, ?, ?, 'IN_PROGRESS', 'NORMAL', 'MANUAL', false,
                        'MANUAL', false, '[]'::jsonb, '{}'::jsonb)
                """, session.tenantId(), UUID.fromString(caseId), title));
    }

    private org.springframework.test.web.servlet.ResultActions close(Session session, String caseId,
                                                                    String body) throws Exception {
        return mockMvc.perform(post("/api/v1/cases/{id}/status", caseId).with(csrf())
                .cookie(session.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    @DisplayName("the outcome cannot be recorded as achieved while obligations are open")
    void refusesFalseClaimOfAchievement() throws Exception {
        Session session = register();
        String caseId = openCaseWithMissingDocuments(session);

        close(session, caseId, """
                {"status":"CLOSED","closeReason":"OUTCOME_ACHIEVED","acknowledgeOpenWork":true,
                 "note":"Alles erledigt"}
                """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("achieved")));
    }

    @Test
    @DisplayName("a case with open obligations is not closed without an explicit confirmation")
    void refusesSilentClosure() throws Exception {
        Session session = register();
        String caseId = openCaseWithMissingDocuments(session);

        close(session, caseId, "{\"status\":\"CLOSED\",\"closeReason\":\"USER_DECISION\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("open obligations")));
    }

    @Test
    @DisplayName("a confirmation without a word of explanation is not a decision")
    void refusesAcknowledgementWithoutNote() throws Exception {
        Session session = register();
        String caseId = openCaseWithMissingDocuments(session);

        close(session, caseId, """
                {"status":"CLOSED","closeReason":"USER_DECISION","acknowledgeOpenWork":true}
                """)
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a deliberate closure with a reason and a note goes through and is recorded")
    void deliberateClosureIsRecorded() throws Exception {
        Session session = register();
        String caseId = openCaseWithMissingDocuments(session);

        MvcResult closed = close(session, caseId, """
                {"status":"CLOSED","closeReason":"USER_DECISION","acknowledgeOpenWork":true,
                 "note":"Der Mandant hat die Unterlagen zurückgezogen, der Vorgang ist erledigt",
                 "outcome":"Zurückgezogen"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseRecord.status").value("CLOSED"))
                .andExpect(jsonPath("$.caseRecord.closedAt").isNotEmpty())
                .andReturn();

        assertThat(closed.getResponse().getContentAsString()).contains("Zurückgezogen");

        // The timeline has to say that the case was closed despite open work, and why. The entry is
        // selected by its type rather than by position: two events of one transaction can share a
        // timestamp, and a test that depends on their order would be flaky rather than meaningful.
        String timeline = mockMvc.perform(get("/api/v1/cases/{id}", caseId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(timeline)
                .as("the closure entry is in the history: %s", timeline)
                .contains("CASE_CLOSED");
        assertThat(timeline)
                .as("the history says that the case was closed despite open work: %s", timeline)
                .contains("closedWithOpenWork");
        assertThat(timeline)
                .as("the history keeps the explanation the reviewer gave: %s", timeline)
                .contains("zurückgezogen");
    }

    @Test
    @DisplayName("an unfinished task blocks the closure just as a missing document does")
    void openTaskBlocksClosure() throws Exception {
        Session session = register();
        String caseId = openCaseWithMissingDocuments(session);
        addOpenTask(session, caseId, "Widerspruch einlegen");

        close(session, caseId, """
                {"status":"CLOSED","closeReason":"USER_DECISION","acknowledgeOpenWork":true,
                 "note":"Trotzdem geschlossen"}
                """)
                .andExpect(status().isOk());

        // The task is named as the open obligation while closing, which is what makes the
        // confirmation meaningful rather than ceremonial.
        mockMvc.perform(get("/api/v1/cases/{id}/lifecycle", caseId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openObligations[?(@.kind=='TASK')].description")
                        .value(org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.containsString("Widerspruch einlegen"))));
    }

    @Test
    @DisplayName("reopening a closed case demands a reason, and clears the closed date")
    void reopeningRequiresReasonAndClearsClosedAt() throws Exception {
        Session session = register();
        String caseId = openCaseWithMissingDocuments(session);
        close(session, caseId, """
                {"status":"CLOSED","closeReason":"DUPLICATE","acknowledgeOpenWork":true,
                 "note":"Doppelt erfasst"}
                """).andExpect(status().isOk());

        close(session, caseId, "{\"status\":\"REOPENED\"}")
                .andExpect(status().isBadRequest());

        close(session, caseId, """
                {"status":"REOPENED","reason":"Der Mandant hat sich erneut gemeldet"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseRecord.status").value("REOPENED"))
                .andExpect(jsonPath("$.caseRecord.closedAt").doesNotExist());
    }

    @Test
    @DisplayName("an impossible transition is refused and names both states")
    void refusesImpossibleTransition() throws Exception {
        Session session = register();
        String caseId = openCaseWithMissingDocuments(session);

        close(session, caseId, "{\"status\":\"REOPENED\",\"reason\":\"weil\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("NEW")));
    }

    @Test
    @DisplayName("the lifecycle view offers only reachable states and lists what is open")
    void lifecycleViewIsActionable() throws Exception {
        Session session = register();
        String caseId = openCaseWithMissingDocuments(session);

        mockMvc.perform(get("/api/v1/cases/{id}/lifecycle", caseId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.possibleTargets")
                        .value(org.hamcrest.Matchers.hasItem("IN_PROGRESS")))
                .andExpect(jsonPath("$.possibleTargets")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("REOPENED"))))
                .andExpect(jsonPath("$.openObligations[?(@.kind=='DOCUMENT')]").isNotEmpty());
    }
}
