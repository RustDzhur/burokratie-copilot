package de.buerokratiecopilot.identity.onboarding.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

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

/**
 * Onboarding over HTTP (spec §2, §3; A-001).
 *
 * <p>The route that matters here is the bootstrap, and what matters about it is the guard:
 * {@code hasFinishedAdministrator} is a property of the <strong>installation</strong> and not of an account,
 * so without it this would be an open account-creation endpoint on every running system, reachable with no
 * session and no invitation. <strong>The guard is the whole reason the route may be public</strong>, and it is
 * the assertion this test exists for.</p>
 *
 * <p>It cannot be exercised in both directions here: the shared test database already holds accounts, so an
 * administrator exists and the bootstrap is refused. That is the direction worth asserting, and the test
 * <strong>derives the expected answer from the installation's own status</strong> instead of assuming one —
 * a test that hard-coded "refused" would pass on a machine where the guard had been removed from a fresh
 * installation's path, and one that hard-coded "allowed" would fail on every real system.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OnboardingControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private String uniqueSlug() {
        return "konto-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String body(String slug) {
        return """
                {"slug":"%s","accountName":"Muster GmbH","accountType":"COMPANY",
                 "adminEmail":"admin-%s@example.de","adminDisplayName":"Erste Person",
                 "language":"de","timeZone":"Europe/Berlin","countryCode":"DE"}
                """.formatted(slug, UUID.randomUUID().toString().substring(0, 8));
    }

    // ---------------------------------------------------------------- the status

    @Test
    @DisplayName("the installation says whether it still has to be set up, and the answer is consistent")
    void theStatusIsConsistent() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/public/onboarding/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasAdministrator").isBoolean())
                .andExpect(jsonPath("$.bootstrapAvailable").isBoolean())
                .andExpect(jsonPath("$.note").isNotEmpty())
                .andReturn();

        boolean finished = JsonPath.read(result.getResponse().getContentAsString(), "$.hasAdministrator");
        boolean available = JsonPath.read(result.getResponse().getContentAsString(),
                "$.bootstrapAvailable");
        org.assertj.core.api.Assertions.assertThat(available)
                .as("a first-run screen that offers a form which will be refused is worse than one that "
                        + "says the installation is already set up")
                .isEqualTo(!finished);
    }

    // ---------------------------------------------------------------- the guard

    @Test
    @DisplayName("the bootstrap is refused exactly when an administrator exists")
    void theBootstrapIsRefusedOnceAnAdministratorExists() throws Exception {
        boolean finished = jdbc.queryForObject("""
                select exists (select 1 from users where status <> 'DELETED')
                """, Boolean.class);

        MvcResult attempt = mockMvc.perform(post("/api/v1/public/onboarding/bootstrap")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(uniqueSlug())))
                .andReturn();

        if (finished) {
            // The security-relevant direction, and the one this installation is in: without the guard this
            // route would be an open account-creation endpoint.
            org.assertj.core.api.Assertions.assertThat(attempt.getResponse().getStatus())
                    .as("with an administrator present the bootstrap must be refused, or anybody who can "
                            + "reach the host can create an account")
                    .isEqualTo(409);
        }
        else {
            org.assertj.core.api.Assertions.assertThat(attempt.getResponse().getStatus())
                    .as("on an installation with no administrator the bootstrap is the way in")
                    .isEqualTo(201);
        }
    }

    @Test
    @DisplayName("the bootstrap needs a slug, a name and an address before anything is created")
    void theBootstrapNeedsItsFields() throws Exception {
        mockMvc.perform(post("/api/v1/public/onboarding/bootstrap")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountName\":\"Ohne Kürzel\",\"adminEmail\":\"a@example.de\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/public/onboarding/bootstrap")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"x\",\"adminEmail\":\"a@example.de\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- creating an account

    @Test
    @DisplayName("an account is created and says that the address has to be confirmed")
    void anAccountIsCreated() throws Exception {
        String slug = uniqueSlug();

        MvcResult created = mockMvc.perform(post("/api/v1/public/onboarding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(slug)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantId").isNotEmpty())
                .andExpect(jsonPath("$.workspaceId").isNotEmpty())
                .andExpect(jsonPath("$.adminUserId").isNotEmpty())
                .andExpect(jsonPath("$.note").isNotEmpty())
                .andReturn();

        String tenantId = JsonPath.read(created.getResponse().getContentAsString(), "$.tenantId");
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("""
                select slug from tenants where id = ?::uuid
                """, String.class, tenantId)).isEqualTo(slug);
    }

    @Test
    @DisplayName("creating an account without its fields is refused")
    void creatingWithoutFieldsIsRefused() throws Exception {
        mockMvc.perform(post("/api/v1/public/onboarding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountName\":\"Ohne Kürzel\",\"adminEmail\":\"a@example.de\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/public/onboarding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"x\",\"accountName\":\"Ohne Adresse\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- completing a bootstrap

    @Test
    @DisplayName("completing without a token is refused, because the token is what authenticates it")
    void completingWithoutATokenIsRefused() throws Exception {
        // There is no route that takes an address and sets a password, because that would be a way to take
        // over an account that is waiting to be set up.
        mockMvc.perform(post("/api/v1/public/onboarding/bootstrap/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"EinSicheresPasswort-2026!\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("completing with a token nobody issued is refused")
    void completingWithAForeignTokenIsRefused() throws Exception {
        mockMvc.perform(post("/api/v1/public/onboarding/bootstrap/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"bcboot_von-niemandem\",\"password\":"
                                + "\"EinSicheresPasswort-2026!\",\"displayName\":\"Niemand\"}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("completing with a short password is refused")
    void completingWithAShortPasswordIsRefused() throws Exception {
        mockMvc.perform(post("/api/v1/public/onboarding/bootstrap/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"bcboot_irgendwas\",\"password\":\"kurz\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- what is not public

    @Test
    @DisplayName("an account cannot be looked up by its address without a session")
    void anAccountIsNotLookedUpByAddress() throws Exception {
        // Answering 'does a company with this name use this software' is a question about somebody else's
        // business, and it is deliberately not answered here.
        mockMvc.perform(get("/api/v1/public/onboarding/by-slug/{slug}", "muster-gmbh"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("all of this is reachable without a session, which is why the bootstrap needs its guard")
    void allOfThisWorksWithoutASession() throws Exception {
        // A person who has no account yet cannot authenticate, so these routes are public by necessity - and
        // that is exactly why the guard on the bootstrap is the thing the test above checks.
        mockMvc.perform(get("/api/v1/public/onboarding/status")).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/public/onboarding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(uniqueSlug())))
                .andExpect(status().isCreated());
    }
}
