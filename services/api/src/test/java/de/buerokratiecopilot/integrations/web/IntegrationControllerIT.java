package de.buerokratiecopilot.integrations.web;

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

import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * Integrations over HTTP (spec §18, §20).
 *
 * <p>What this checks beyond the routes: the catalogue <strong>tells the truth about what exists</strong>,
 * an adapter this software does not have is recorded as {@code NOT_SUPPORTED} rather than left looking
 * unconfigured, and <strong>credentials go in and never come back out</strong> — the answer says whether any
 * were stored, which is the question an interface has, and not what they are.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IntegrationControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    private record Session(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private Session register() throws Exception {
        String email = "int-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Anbindung Test","accountType":"COMPANY"}
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

    // ---------------------------------------------------------------- the catalogue

    @Test
    @DisplayName("the catalogue says which adapters exist and which do not")
    void theCatalogueTellsTheTruth() throws Exception {
        Session session = register();

        mockMvc.perform(get("/api/v1/integrations/catalogue").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.implementedCount").isNumber())
                .andExpect(jsonPath("$.notImplementedCount").isNumber())
                .andExpect(jsonPath("$.EMAIL").isArray());
    }

    @Test
    @DisplayName("what an adapter needs from the owner is served, in order")
    void theRequirementsAreServed() throws Exception {
        Session session = register();

        mockMvc.perform(get("/api/v1/integrations/ACCOUNTING_DATEV/requirements").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.implemented").value(false))
                .andExpect(jsonPath("$.steps").isArray())
                .andExpect(jsonPath("$.steps[0]").isNotEmpty())
                .andExpect(jsonPath("$.conclusion").isNotEmpty());
    }

    @Test
    @DisplayName("an implemented adapter says so")
    void anImplementedAdapterSaysSo() throws Exception {
        Session session = register();

        mockMvc.perform(get("/api/v1/integrations/IMAP/requirements").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.implemented").value(true))
                .andExpect(jsonPath("$.steps").isArray());
    }

    @Test
    @DisplayName("an unknown adapter is reported rather than invented")
    void anUnknownAdapterIsReported() throws Exception {
        Session session = register();

        mockMvc.perform(get("/api/v1/integrations/TELEPATHY/requirements").cookie(session.cookie()))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- what does not exist

    @Test
    @DisplayName("an adapter this software does not have is recorded as not supported")
    void anAbsentAdapterIsRecordedHonestly() throws Exception {
        Session session = register();

        MvcResult created = mockMvc.perform(post("/api/v1/integrations/unsupported").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"ACCOUNTING_DATEV\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("NOT_SUPPORTED"))
                .andReturn();

        org.assertj.core.api.Assertions.assertThat(JsonPath.read(
                        created.getResponse().getContentAsString(), "$.note").toString())
                .contains("Nicht enthalten");

        // 'Not configured' would send somebody looking for a setting that does not exist.
        mockMvc.perform(get("/api/v1/integrations").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("NOT_SUPPORTED"));
    }

    @Test
    @DisplayName("an adapter that exists is not declared unsupported")
    void anExistingAdapterIsNotDeclaredUnsupported() throws Exception {
        Session session = register();

        mockMvc.perform(post("/api/v1/integrations/unsupported").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"SMTP\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("declaring the same absent adapter twice leaves one row")
    void declaringTwiceLeavesOneRow() throws Exception {
        Session session = register();
        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/api/v1/integrations/unsupported").with(csrf())
                            .cookie(session.cookie())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"provider\":\"CRM_HUBSPOT\"}"))
                    .andExpect(status().isCreated());
        }

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("""
                select count(*) from integrations where tenant_id = ? and provider = 'CRM_HUBSPOT'
                """, Integer.class, session.tenantId())).isEqualTo(1);
    }

    // ---------------------------------------------------------------- connecting

    @Test
    @DisplayName("connecting stores the credentials and never returns them")
    void connectingNeverReturnsCredentials() throws Exception {
        Session session = register();
        String secret = "geheim-" + UUID.randomUUID().toString().substring(0, 8);

        MvcResult created = mockMvc.perform(post("/api/v1/integrations").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"provider":"IMAP","category":"EMAIL","displayName":"Eingang",
                                 "credentialType":"PASSWORD",
                                 "fields":{"host":"imap.example.de","password":"%s"},
                                 "scopes":["mail:read"],"connectedAccount":"post@example.de"}
                                """.formatted(secret)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.integrationId").isNotEmpty())
                .andExpect(jsonPath("$.status").isNotEmpty())
                .andExpect(jsonPath("$.credentialsStored").value(true))
                .andReturn();

        // The answer says whether anything was stored - the question an interface has - and not what it was.
        org.assertj.core.api.Assertions.assertThat(created.getResponse().getContentAsString())
                .doesNotContain(secret);

        String stored = jdbc.queryForObject("""
                select c.encrypted_payload from integration_credentials c
                join integrations i on i.id = c.integration_id
                where c.tenant_id = ?
                """, String.class, session.tenantId());
        // The credentials live in their own table rather than in the connection row, and what is kept there
        // is the encrypted payload - the secret itself is not in it.
        org.assertj.core.api.Assertions.assertThat(stored).isNotNull().doesNotContain(secret);
    }

    @Test
    @DisplayName("connecting without a provider is refused")
    void connectingWithoutAProviderIsRefused() throws Exception {
        Session session = register();

        mockMvc.perform(post("/api/v1/integrations").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"EMAIL\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a connection can be switched off")
    void aConnectionCanBeSwitchedOff() throws Exception {
        Session session = register();
        String secret = "geheim-" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult created = mockMvc.perform(post("/api/v1/integrations").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"provider":"IMAP","category":"EMAIL","displayName":"Eingang",
                                 "credentialType":"PASSWORD","fields":{"password":"%s"}}
                                """.formatted(secret)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID integrationId = UUID.fromString(JsonPath.read(
                created.getResponse().getContentAsString(), "$.integrationId"));

        mockMvc.perform(post("/api/v1/integrations/{id}/disable", integrationId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Nicht mehr benötigt\"}"))
                .andExpect(status().isNoContent());

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("""
                select status from integrations where id = ?
                """, String.class, integrationId)).isEqualTo("DISABLED");
    }

    @Test
    @DisplayName("a synchronisation reports the run's own status rather than success")
    void aSyncReportsItsOwnStatus() throws Exception {
        Session session = register();
        MvcResult created = mockMvc.perform(post("/api/v1/integrations").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"provider":"IMAP","category":"EMAIL","displayName":"Eingang",
                                 "credentialType":"PASSWORD","fields":{"password":"x"}}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        UUID integrationId = UUID.fromString(JsonPath.read(
                created.getResponse().getContentAsString(), "$.integrationId"));

        // An integration that half-worked has to be able to say so, or nobody finds out until the figures
        // are wrong.
        mockMvc.perform(post("/api/v1/integrations/{id}/sync", integrationId).with(csrf())
                        .cookie(session.cookie()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.runId").isNotEmpty())
                .andExpect(jsonPath("$.status").isNotEmpty());
    }

    // ---------------------------------------------------------------- what is refused

    @Test
    @DisplayName("without a session nothing is read or connected")
    void withoutASessionNothingHappens() throws Exception {
        mockMvc.perform(get("/api/v1/integrations")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/integrations/catalogue")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/integrations").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"IMAP\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an account sees its own connections and no others")
    void accountsAreSeparated() throws Exception {
        Session first = register();
        Session second = register();
        mockMvc.perform(post("/api/v1/integrations/unsupported").with(csrf())
                        .cookie(first.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"ACCOUNTING_DATEV\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/integrations").cookie(second.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("the integration permissions exist and are held, which is what makes these reachable")
    void theIntegrationPermissionsAreHeld() {
        Integer held = jdbc.queryForObject("""
                select count(*) from role_permissions rp
                join roles r on r.id = rp.role_id
                join permissions p on p.id = rp.permission_id
                where p.code in ('integrations.view','integrations.configure','integrations.connect')
                  and r.tenant_id is null
                """, Integer.class);

        org.assertj.core.api.Assertions.assertThat(held)
                .as("the integration permissions must be granted to a system role, or these are dead")
                .isGreaterThan(0);
    }
}
