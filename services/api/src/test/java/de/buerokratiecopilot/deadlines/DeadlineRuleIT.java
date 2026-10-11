package de.buerokratiecopilot.deadlines;

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
 * Verifies the deadline register and the dates counted from it (spec §13.2).
 *
 * <p>The acceptance criterion of this task is a statement about honesty rather than about arithmetic:
 * a date counted from a rule nobody has verified must not look like a settled legal deadline. The test
 * therefore walks the whole path — enter a rule, count a date, see it marked as assumed, verify the
 * rule, count again and get a confirmed date.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DeadlineRuleIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    private record Session(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private Session register() throws Exception {
        String email = "deadline-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Fristen Test","accountType":"COMPANY"}
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

    /** A task to hang deadlines on: the schema requires every deadline to belong to one. */
    private String createTask(Session session) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/tasks").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Frist wahrnehmen\",\"completionCriteria\":\"Frist ist gewahrt\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    }

    private String createRule(Session session, String code, String legalBasis) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/deadline-rules").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","name":{"de":"Testfrist"},
                                 "legalBasis":%s,"periodValue":14,"periodUnit":"DAYS",
                                 "countingMode":"CALENDAR_DAYS","startEvent":"RECEIPT_DATE"}
                                """.formatted(code, legalBasis == null ? "null" : "\"" + legalBasis + "\"")))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    }

    @Test
    @DisplayName("the register starts empty: the platform ships no legal rules of its own")
    void registerStartsEmpty() throws Exception {
        Session session = register();

        mockMvc.perform(get("/api/v1/deadline-rules").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("an entered rule is unverified, and a date from it is an assumption")
    void unverifiedRuleProducesAnAssumedDate() throws Exception {
        Session session = register();
        String taskId = createTask(session);
        createRule(session, "test-frist-14", "Eigene Quelle des Mandanten");

        MvcResult computed = mockMvc.perform(post("/api/v1/deadlines/compute").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ruleCode":"test-frist-14","taskId":"%s","startDate":"2026-03-02"}
                                """.formatted(taskId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deadline.type").value("ASSUMED_LEGAL"))
                .andExpect(jsonPath("$.deadline.requiresVerification").value(true))
                .andExpect(jsonPath("$.deadline.dueDate").value("2026-03-16"))
                .andExpect(jsonPath("$.ruleVerified").value(false))
                .andReturn();

        assertThat(computed.getResponse().getContentAsString())
                .as("the explanation has to say that the date is provisional")
                .contains("nicht bestätigt");
    }

    @Test
    @DisplayName("a verified rule produces a confirmed legal date")
    void verifiedRuleProducesAConfirmedDate() throws Exception {
        Session session = register();
        String taskId = createTask(session);
        String ruleId = createRule(session, "test-frist-geprueft", "Geprüfte Quelle");

        mockMvc.perform(post("/api/v1/deadline-rules/{id}/verify", ruleId).with(csrf())
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true))
                .andExpect(jsonPath("$.requiresVerification").value(false));

        mockMvc.perform(post("/api/v1/deadlines/compute").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ruleCode":"test-frist-geprueft","taskId":"%s","startDate":"2026-03-02"}
                                """.formatted(taskId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deadline.type").value("CONFIRMED_LEGAL"))
                .andExpect(jsonPath("$.deadline.requiresVerification").value(false))
                .andExpect(jsonPath("$.deadline.dueDate").value("2026-03-16"))
                .andExpect(jsonPath("$.ruleVerified").value(true));
    }

    @Test
    @DisplayName("a rule without a stated source cannot be verified, because there is nothing to check")
    void ruleWithoutLegalBasisCannotBeVerified() throws Exception {
        Session session = register();
        String ruleId = createRule(session, "test-frist-ohne-quelle", null);

        mockMvc.perform(post("/api/v1/deadline-rules/{id}/verify", ruleId).with(csrf())
                        .cookie(session.cookie()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("legal basis")));
    }

    @Test
    @DisplayName("a rule without a period is refused, and cannot be verified into existence")
    void ruleWithoutPeriodIsRefused() throws Exception {
        Session session = register();

        mockMvc.perform(post("/api/v1/deadline-rules").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"ohne-frist","name":{"de":"Ohne Frist"},
                                 "legalBasis":"Quelle","periodUnit":"DAYS"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("dates resting on an unverified rule are listed for the person who checks them")
    void pendingVerificationIsListed() throws Exception {
        Session session = register();
        String taskId = createTask(session);
        createRule(session, "test-frist-offen", "Quelle");
        mockMvc.perform(post("/api/v1/deadlines/compute").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ruleCode":"test-frist-offen","taskId":"%s","startDate":"2026-03-02"}
                                """.formatted(taskId)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/deadlines/pending-verification").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].requiresVerification").value(true));

        mockMvc.perform(get("/api/v1/deadline-rules/pending-verification").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code=='test-frist-offen')]").isNotEmpty());
    }

    @Test
    @DisplayName("a date a person records carries no legal claim and says so")
    void manualDeadlineIsInternal() throws Exception {
        Session session = register();

        String taskId = createTask(session);

        mockMvc.perform(post("/api/v1/deadlines").param("note", "Interne Zielmarke")
                        .with(csrf()).cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDate\":\"2026-04-01\",\"taskId\":\"%s\"}".formatted(taskId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("INTERNAL"))
                .andExpect(jsonPath("$.source").value("MANUAL"))
                .andExpect(jsonPath("$.requiresVerification").value(false))
                .andExpect(jsonPath("$.dueDate").value("2026-04-01"));
    }

    @Test
    @DisplayName("computing from an unknown rule is a clear not-found, not a silent zero")
    void unknownRuleIsNotFound() throws Exception {
        Session session = register();

        String taskId = createTask(session);

        mockMvc.perform(post("/api/v1/deadlines/compute").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ruleCode":"gibt-es-nicht","taskId":"%s","startDate":"2026-03-02"}
                                """.formatted(taskId)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a deadline without a start date is refused instead of counted from today")
    void refusesDeadlineWithoutStartDate() throws Exception {
        Session session = register();
        String taskId = createTask(session);
        createRule(session, "test-frist-start", "Quelle");

        mockMvc.perform(post("/api/v1/deadlines/compute").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ruleCode\":\"test-frist-start\"}"))
                .andExpect(status().isBadRequest());
    }
}
