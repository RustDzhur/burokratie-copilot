package de.buerokratiecopilot.identity.mfa.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
 * The second factor over HTTP (spec §33, A-014).
 *
 * <p>What this checks beyond the routes: an enrolment is <strong>not a factor until a code from it is
 * confirmed</strong>, the secret and the recovery codes are <strong>shown once</strong>, and every route
 * acts on the caller — there is no path that names somebody else, because an endpoint that could switch off
 * another person's second factor is the one an attacker would want.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MfaControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    private record Session(Cookie cookie, UUID tenantId, UUID userId, String email) {
    }

    private Session register() throws Exception {
        String email = "mfa-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"MFA Test","accountType":"COMPANY"}
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
        return new Session(login.getResponse().getCookie("bc_session"), tenantId, userId, email);
    }

    /** The enrolment, with the secret that exists only in this answer. */
    private record Enrolment(UUID methodId, String secret) {
    }

    private Enrolment enrol(Session session) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/mfa/enrol").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"Telefon\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return new Enrolment(
                UUID.fromString(JsonPath.read(created.getResponse().getContentAsString(), "$.methodId")),
                JsonPath.read(created.getResponse().getContentAsString(), "$.secret"));
    }

    /** The code an authenticator application would show, computed the way the service does. */
    private String codeFor(String secret) {
        return de.buerokratiecopilot.identity.mfa.Totp.code(
                MfaService_decode(secret), de.buerokratiecopilot.identity.mfa.Totp.stepAt(
                        java.time.Instant.now().getEpochSecond()));
    }

    private static byte[] MfaService_decode(String secret) {
        return de.buerokratiecopilot.identity.mfa.MfaService.decodeBase32(secret);
    }

    // ---------------------------------------------------------------- the routes

    @Test
    @DisplayName("a fresh account has no second factor and says so")
    void aFreshAccountHasNoFactor() throws Exception {
        Session session = register();

        mockMvc.perform(get("/api/v1/mfa").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.note").isNotEmpty());
    }

    @Test
    @DisplayName("an enrolment returns a secret and a provisioning URI, once")
    void anEnrolmentReturnsASecret() throws Exception {
        Session session = register();

        MvcResult created = mockMvc.perform(post("/api/v1/mfa/enrol").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"Telefon\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.methodId").isNotEmpty())
                .andExpect(jsonPath("$.secret").isNotEmpty())
                .andExpect(jsonPath("$.provisioningUri").isNotEmpty())
                .andExpect(jsonPath("$.note").isNotEmpty())
                .andReturn();

        String secret = JsonPath.read(created.getResponse().getContentAsString(), "$.secret");
        String stored = jdbc.queryForObject("""
                select secret_encrypted from mfa_methods where user_id = ?
                """, String.class, session.userId());
        // The secret generates every future code; one that could be read again would be one anybody can use.
        org.assertj.core.api.Assertions.assertThat(stored).doesNotContain(secret);
    }

    @Test
    @DisplayName("starting an enrolment is not activation")
    void anEnrolmentIsNotActivation() throws Exception {
        Session session = register();
        enrol(session);

        // Treating an unconfirmed secret as a factor locks people out of their own accounts.
        mockMvc.perform(get("/api/v1/mfa").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    @DisplayName("the list of factors contains no secret")
    void theListContainsNoSecret() throws Exception {
        Session session = register();
        Enrolment enrolment = enrol(session);

        MvcResult listed = mockMvc.perform(get("/api/v1/mfa/methods").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("TOTP"))
                .andReturn();

        org.assertj.core.api.Assertions.assertThat(listed.getResponse().getContentAsString())
                .doesNotContain(enrolment.secret());
    }

    // ---------------------------------------------------------------- confirming

    @Test
    @DisplayName("confirming with the right code activates the factor and issues the recovery codes once")
    void confirmingActivatesTheFactor() throws Exception {
        Session session = register();
        Enrolment enrolment = enrol(session);

        MvcResult confirmed = mockMvc.perform(post("/api/v1/mfa/methods/{id}/confirm", enrolment.methodId())
                        .with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"%s\"}".formatted(codeFor(enrolment.secret()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recoveryCodes").isArray())
                .andExpect(jsonPath("$.recoveryCodes.length()").value(10))
                .andReturn();

        org.assertj.core.api.Assertions.assertThat(
                        (java.util.List<?>) JsonPath.read(
                                confirmed.getResponse().getContentAsString(), "$.recoveryCodes"))
                .as("the codes exist for the telephone that was lost")
                .hasSize(10);

        mockMvc.perform(get("/api/v1/mfa").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.remainingRecoveryCodes").value(10));
    }

    @Test
    @DisplayName("a wrong code does not activate anything")
    void aWrongCodeDoesNotActivate() throws Exception {
        Session session = register();
        Enrolment enrolment = enrol(session);

        mockMvc.perform(post("/api/v1/mfa/methods/{id}/confirm", enrolment.methodId()).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"000000\"}"))
                .andExpect(status().is4xxClientError());

        mockMvc.perform(get("/api/v1/mfa").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    @DisplayName("confirming without a code is refused")
    void confirmingWithoutACodeIsRefused() throws Exception {
        Session session = register();
        Enrolment enrolment = enrol(session);

        mockMvc.perform(post("/api/v1/mfa/methods/{id}/confirm", enrolment.methodId()).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- verifying

    @Test
    @DisplayName("a valid code verifies, and a wrong one answers rather than failing")
    void verifyingAnswersRatherThanFailing() throws Exception {
        Session session = register();
        Enrolment enrolment = enrol(session);
        mockMvc.perform(post("/api/v1/mfa/methods/{id}/confirm", enrolment.methodId()).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"%s\"}".formatted(codeFor(enrolment.secret()))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/mfa/verify").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"%s\"}".formatted(codeFor(enrolment.secret()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.method").value("TOTP"));

        // A wrong code is the expected case at a sign-in screen, not an error.
        mockMvc.perform(post("/api/v1/mfa/verify").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"000000\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false));
    }

    @Test
    @DisplayName("a recovery code verifies exactly once")
    void aRecoveryCodeWorksOnce() throws Exception {
        Session session = register();
        Enrolment enrolment = enrol(session);
        MvcResult confirmed = mockMvc.perform(post("/api/v1/mfa/methods/{id}/confirm", enrolment.methodId())
                        .with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"%s\"}".formatted(codeFor(enrolment.secret()))))
                .andExpect(status().isOk())
                .andReturn();
        String recovery = ((java.util.List<String>) JsonPath.read(
                confirmed.getResponse().getContentAsString(), "$.recoveryCodes")).getFirst();

        mockMvc.perform(post("/api/v1/mfa/verify").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"%s\"}".formatted(recovery)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.method").value("RECOVERY_CODE"));

        // A recovery code that works twice is a password somebody wrote down.
        mockMvc.perform(post("/api/v1/mfa/verify").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"%s\"}".formatted(recovery)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false));
    }

    // ---------------------------------------------------------------- switching off

    @Test
    @DisplayName("switching a factor off takes it away and invalidates its recovery codes")
    void switchingOffTakesEverythingAway() throws Exception {
        Session session = register();
        Enrolment enrolment = enrol(session);
        MvcResult confirmed = mockMvc.perform(post("/api/v1/mfa/methods/{id}/confirm", enrolment.methodId())
                        .with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"%s\"}".formatted(codeFor(enrolment.secret()))))
                .andExpect(status().isOk())
                .andReturn();
        String recovery = ((java.util.List<String>) JsonPath.read(
                confirmed.getResponse().getContentAsString(), "$.recoveryCodes")).getFirst();

        mockMvc.perform(delete("/api/v1/mfa/methods/{id}", enrolment.methodId()).with(csrf())
                        .cookie(session.cookie()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/mfa").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.remainingRecoveryCodes").value(0));

        mockMvc.perform(post("/api/v1/mfa/verify").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"%s\"}".formatted(recovery)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false));
    }

    @Test
    @DisplayName("a factor of another user is not reachable through these routes")
    void anotherUsersFactorIsNotReachable() throws Exception {
        Session first = register();
        Session second = register();
        Enrolment enrolment = enrol(first);

        // Every route acts on the caller, so the second user cannot confirm or disable what belongs to the
        // first - and there is no path that names somebody else to try.
        mockMvc.perform(post("/api/v1/mfa/methods/{id}/confirm", enrolment.methodId()).with(csrf())
                        .cookie(second.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"000000\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/mfa/methods").cookie(second.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    // ---------------------------------------------------------------- requiring it

    @Test
    @DisplayName("the account can require a second factor, and it is the account's own decision")
    void theAccountCanRequireIt() throws Exception {
        Session first = register();
        Session second = register();

        mockMvc.perform(post("/api/v1/mfa/requirement").with(csrf())
                        .cookie(first.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"required\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.required").value(true));

        mockMvc.perform(get("/api/v1/mfa").cookie(second.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.required").value(false));
    }

    // ---------------------------------------------------------------- what is refused

    @Test
    @DisplayName("without a session nothing is read or changed")
    void withoutASessionNothingHappens() throws Exception {
        mockMvc.perform(get("/api/v1/mfa")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/mfa/enrol").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/mfa/verify").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"123456\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/mfa/requirement").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"required\":true}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("verifying without a code is refused")
    void verifyingWithoutACodeIsRefused() throws Exception {
        Session session = register();

        mockMvc.perform(post("/api/v1/mfa/verify").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
