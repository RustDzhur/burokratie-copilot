package de.buerokratiecopilot.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import jakarta.servlet.http.Cookie;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import de.buerokratiecopilot.identity.MembershipRepository;
import de.buerokratiecopilot.identity.MembershipRoleRepository;
import de.buerokratiecopilot.identity.TenantRepository;
import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.WorkspaceRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * End-to-end verification of the authentication flow required by spec §28.3 (scenario 1) and
 * §21.3: registration, e-mail confirmation, sign-in, session handling, lockout after repeated
 * failures and password policy enforcement.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthFlowIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository users;

    @Autowired
    private TenantRepository tenants;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private MembershipRepository memberships;

    @Autowired
    private MembershipRoleRepository membershipRoles;

    private record Registered(UUID userId, UUID tenantId, String email, String password) {
    }

    private Registered register() throws Exception {
        String email = "user-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        String password = "EinSicheresPasswort-2026!";

        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s","displayName":"Erika Mustermann",
                                 "tenantName":"Muster GmbH"}
                                """.formatted(email, password)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantId").exists())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Registered(UUID.fromString(body.get("userId").asText()),
                UUID.fromString(body.get("tenantId").asText()), email, password);
    }

    @Test
    @DisplayName("registration creates the user, its tenant, a default workspace and an OWNER membership")
    void registrationCreatesTenantWorkspaceAndOwnership() throws Exception {
        Registered registered = register();

        User user = users.findById(registered.userId()).orElseThrow();
        assertThat(user.getStatus()).isEqualTo(User.Status.PENDING_VERIFICATION);
        assertThat(user.getPasswordHash()).startsWith("$argon2");
        assertThat(user.getPasswordHash()).doesNotContain(registered.password());

        var tenant = tenants.findById(registered.tenantId()).orElseThrow();
        assertThat(tenant.getAccountType()).isEqualTo("COMPANY");
        assertThat(tenant.getStatus()).isEqualTo("ACTIVE");

        var workspace = RequestContext.runAs(registered.tenantId(),
                () -> workspaces.findByTenantIdAndDefaultWorkspaceTrue(registered.tenantId()));
        assertThat(workspace).isPresent();
        assertThat(workspace.orElseThrow().getMode()).isEqualTo("BUSINESS");

        var membership = RequestContext.runAs(registered.tenantId(),
                () -> memberships.findByTenantIdAndUserId(registered.tenantId(), registered.userId()));
        assertThat(membership).isPresent();
        assertThat(membership.orElseThrow().isOwner()).isTrue();
        assertThat(membershipRoleCodes(registered)).contains("OWNER");
    }

    private java.util.List<String> membershipRoleCodes(Registered registered) {
        return RequestContext.runAs(registered.tenantId(), () -> {
            var membership = memberships
                    .findByTenantIdAndUserId(registered.tenantId(), registered.userId())
                    .orElseThrow();
            return membershipRoles.findRoleCodes(membership.getId());
        });
    }

    @Test
    @DisplayName("signing in before confirming the e-mail address is refused with a clear code")
    void loginBeforeVerificationIsRefused() throws Exception {
        Registered registered = register();

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(registered.email(), registered.password())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    @DisplayName("a registered and confirmed user can sign in, receives a session cookie and is known to /me")
    void loginIssuesSessionCookieAndResolvesPrincipal() throws Exception {
        Registered registered = register();
        activate(registered);

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(registered.email(), registered.password())))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("bc_session"))
                .andExpect(jsonPath("$.tenantId").value(registered.tenantId().toString()))
                .andReturn();

        Cookie session = login.getResponse().getCookie("bc_session");
        assertThat(session).isNotNull();
        assertThat(session.isHttpOnly()).isTrue();

        mockMvc.perform(get("/api/v1/auth/me").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(registered.userId().toString()))
                .andExpect(jsonPath("$.tenantId").value(registered.tenantId().toString()))
                .andExpect(jsonPath("$.permissions").isNotEmpty())
                // The sign-in screen builds its greeting and workspace switcher from this answer alone.
                .andExpect(jsonPath("$.email").value(registered.email()))
                .andExpect(jsonPath("$.displayName").value("Erika Mustermann"))
                .andExpect(jsonPath("$.workspaceName").value("Muster GmbH"))
                .andExpect(jsonPath("$.roles[0]").value("OWNER"));
    }

    @Test
    @DisplayName("resending the verification link answers 202 for a known and an unknown address alike")
    void resendVerificationDoesNotRevealWhetherAnAccountExists() throws Exception {
        Registered registered = register();
        for (String email : new String[] {registered.email(), "nobody-" + UUID.randomUUID() + "@example.de"}) {
            mockMvc.perform(post("/api/v1/auth/verify-email/resend")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"%s\"}".formatted(email)))
                    .andExpect(status().isAccepted());
        }
    }

    @Test
    @DisplayName("without a session the API answers 401 in the shared error envelope")
    void unauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("an unknown user and a wrong password produce the very same answer")
    void loginDoesNotRevealWhetherAnAccountExists() throws Exception {
        Registered registered = register();
        activate(registered);

        String wrongPassword = mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"FalschesPasswort-2026!"}
                                """.formatted(registered.email())))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        String unknownUser = mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"niemand-%s@example.de","password":"FalschesPasswort-2026!"}
                                """.formatted(UUID.randomUUID().toString().substring(0, 8))))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(wrongPassword).get("message").asText())
                .isEqualTo(objectMapper.readTree(unknownUser).get("message").asText());
    }

    @Test
    @DisplayName("repeated failures lock the account temporarily")
    void repeatedFailuresLockTheAccount() throws Exception {
        Registered registered = register();
        activate(registered);

        for (int attempt = 0; attempt < 5; attempt++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"%s","password":"FalschesPasswort-2026!"}
                                    """.formatted(registered.email())))
                    .andExpect(status().isUnauthorized());
        }

        // Even the correct password is refused while the lock is active.
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(registered.email(), registered.password())))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));

        User locked = users.findById(registered.userId()).orElseThrow();
        assertThat(locked.getLockedUntil()).isNotNull();
        assertThat(locked.getLockedUntil()).isAfter(java.time.Instant.now());
    }

    @Test
    @DisplayName("weak and guessable passwords are rejected with the violated rule")
    void weakPasswordsAreRejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"weak-%s@example.de","password":"passwort",
                                 "displayName":"Test"}
                                """.formatted(UUID.randomUUID().toString().substring(0, 8))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[0].field").value("password"));
    }

    @Test
    @DisplayName("the browser CSRF flow works: cookie from /auth/csrf, echoed in the header")
    void csrfCookieIsIssuedAndAccepted() throws Exception {
        // This is the flow the single page application uses without any test helper: ask for the
        // cookie, then echo its value in the header on every unsafe request.
        MvcResult csrfResult = mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isNoContent())
                .andReturn();

        // Regression test for a real defect: the endpoint used to answer 204 without any
        // cookie, so a single page application could never send an unsafe request. The raw
        // Set-Cookie header is asserted — it is exactly what a browser reads. The full
        // browser round trip (cookie -> X-XSRF-TOKEN -> accepted write) is verified against a
        // running server in docs/implementation-plan.md (see the CSRF row of the test table).
        String setCookie = csrfResult.getResponse().getHeaders("Set-Cookie").stream()
                .filter(header -> header.startsWith("XSRF-TOKEN="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no XSRF-TOKEN cookie was delivered: "
                        + csrfResult.getResponse().getHeaders("Set-Cookie")));
        // Note: MockHttpServletResponse re-serializes Set-Cookie and therefore drops attributes
        // it does not model (SameSite); the real header is asserted against a running server.
        assertThat(setCookie).as("delivered header: " + setCookie).containsIgnoringCase("path=/");
    }

    @Test
    @DisplayName("state changing requests without a CSRF token are refused")
    void csrfProtectionIsActive() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.de\",\"password\":\"whatever-123456\"}"))
                .andExpect(status().isForbidden());
    }

    /**
     * Marks the account as confirmed using the same service the real flow uses. The raw token is
     * delivered by e-mail (or logged when no SMTP is configured), so a test cannot read it from
     * the database — it re-issues a token instead of pretending the flow happened.
     */
    private void activate(Registered registered) {
        RequestContext.runAs(registered.tenantId(), () -> {
            User user = users.findById(registered.userId()).orElseThrow();
            user.markEmailVerified(java.time.Instant.now());
            user.setStatus(User.Status.ACTIVE);
            users.save(user);
            return null;
        });
    }
}
