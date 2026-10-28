package de.buerokratiecopilot.license;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import de.buerokratiecopilot.ingest.IngestService;

/**
 * With a valid licence the same agent surface accepts an API token: the catalogue is readable and
 * a run executes. This is also the regression test for bearer-token authentication, which the
 * documentation promised long before the API implemented it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LicenseActiveIT {

    private static final String SECRET = LicenseTestTokens.newSecret();
    private static final String INSTANCE_ID = UUID.randomUUID().toString();
    private static final Path INSTANCE_FILE;
    private static final String KEY;

    static {
        try {
            INSTANCE_FILE = Files.createTempFile("bc-license-instance", ".id");
            Files.writeString(INSTANCE_FILE, INSTANCE_ID);
            Instant now = Instant.now();
            KEY = LicenseTestTokens.mint(SECRET, "LIC-IT-0001", "Integration Test AG",
                    INSTANCE_ID, "ENTERPRISE", 126, 5000, List.of("agents", "workflow"),
                    now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(30)));
        }
        catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @DynamicPropertySource
    static void licenceProperties(DynamicPropertyRegistry registry) {
        registry.add("burokratie.license.enforced", () -> "true");
        registry.add("burokratie.license.key", () -> KEY);
        registry.add("burokratie.license.secret", () -> SECRET);
        registry.add("burokratie.license.instance-id-file", () -> INSTANCE_FILE.toString());
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private IngestService ingest;

    @Test
    @DisplayName("a valid licence lets an API token read the catalogue, run an agent and see the status")
    void tokenRunsAgent() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        jdbc.update("""
                insert into tenants (id, slug, name, account_type)
                values (?, ?, 'License IT', 'COMPANY')
                """, tenantId, "lic-it-" + tenantId.toString().substring(0, 8));
        jdbc.update("""
                insert into users (id, email, password_hash, status, display_name, email_verified_at)
                values (?, ?, null, 'ACTIVE', 'IT Owner', now())
                """, userId, "license-it-" + userId.toString().substring(0, 8) + "@example.de");
        jdbc.update("""
                insert into workspaces (id, tenant_id, name, mode, is_default)
                values (?, ?, 'Runtime', 'BUSINESS', true)
                """, workspaceId, tenantId);

        IngestService.IssuedToken issued = ingest.issueToken(tenantId, workspaceId, "it-token",
                List.of("agents.view", "agents.run", "documents:write"), 300, null, userId);
        String authorization = "Bearer " + issued.token();

        mockMvc.perform(get("/api/v1/agents").header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(header().string("X-License-Status", "LICENSE_ACTIVE"))
                .andExpect(jsonPath("$").isArray());

        mockMvc.perform(post("/api/v1/agents/doc-detect/run")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trigger\":\"manual\",\"inputs\":{\"fileName\":\"rechnung.pdf\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentId").value("doc-detect"))
                .andExpect(jsonPath("$.ok").value(true));

        mockMvc.perform(get("/api/v1/license"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("LICENSE_ACTIVE"))
                .andExpect(jsonPath("$.licenseId").value("LIC-IT-0001"))
                .andExpect(jsonPath("$.plan").value("ENTERPRISE"));
    }

    @Test
    @DisplayName("the licence does not replace authentication: no token is still 401")
    void missingTokenIsUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/agents"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("an invalid bearer token is refused as a credential, not as a licence problem")
    void invalidTokenIsUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/agents").header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("registration is still reachable and is not part of the agent surface")
    void registrationIsNotGatedByTheLicence() throws Exception {
        String email = "reg-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Regression","accountType":"COMPANY"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        String tenantId = JsonPath.read(result.getResponse().getContentAsString(), "$.tenantId");
        org.assertj.core.api.Assertions.assertThat(tenantId).isNotBlank();
    }
}
