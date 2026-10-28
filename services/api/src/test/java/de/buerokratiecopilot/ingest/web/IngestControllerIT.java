package de.buerokratiecopilot.ingest.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
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

import de.buerokratiecopilot.ingest.WebhookSignature;

/**
 * Delivering a document over HTTP (spec §7, §20).
 *
 * <p>The service had tokens, scopes, rate limits and duplicate detection and no way in: nothing in the web
 * layer referenced it, so the Desktop Bridge pointed at an address that did not exist. These tests are what
 * says whether a machine can actually deliver a document.</p>
 *
 * <p>The token is created directly with the same hash function the service looks it up by, because the
 * question here is whether the endpoint accepts a real token — not whether some other endpoint issues one.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IngestControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private record Device(UUID tenantId, UUID workspaceId, String token) {
    }

    /** A tenant, a workspace and a device token, as an administrator would have issued it. */
    private Device device(String... scopes) {
        UUID tenantId = jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'Einlieferung Test', 'COMPANY') returning id
                """, UUID.class, "in-" + UUID.randomUUID().toString().substring(0, 12));
        UUID organizationId = jdbc.queryForObject("""
                insert into organizations (id, tenant_id, name, country_code, is_own_entity)
                values (gen_random_uuid(), ?, 'Einlieferung GmbH', 'DE', true) returning id
                """, UUID.class, tenantId);
        UUID workspaceId = jdbc.queryForObject("""
                insert into workspaces (id, tenant_id, organization_id, name, mode, is_default, settings)
                values (gen_random_uuid(), ?, ?, 'Standard', 'BUSINESS', true, '{}'::jsonb) returning id
                """, UUID.class, tenantId, organizationId);

        String token = "bc_" + UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        String scopeList = scopes.length == 0 ? "documents:write" : String.join(",", scopes);
        jdbc.update("""
                insert into api_tokens (id, tenant_id, workspace_id, name, token_prefix, token_hash, scopes,
                                        rate_limit_per_minute, is_active)
                values (gen_random_uuid(), ?, ?, 'Bridge', ?, ?, ?::jsonb, 600, true)
                """, tenantId, workspaceId, token.substring(0, 8),
                WebhookSignature.hashApiToken(token),
                "[" + java.util.Arrays.stream(scopeList.split(","))
                        .map(scope -> "\"" + scope + "\"")
                        .collect(java.util.stream.Collectors.joining(",")) + "]");
        return new Device(tenantId, workspaceId, token);
    }

    private MvcResult deliver(Device device, String token, byte[] content, String filename,
                              String key) throws Exception {
        var request = post("/api/v1/documents:ingest")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(content);
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        if (filename != null) {
            request = request.header("X-Filename", filename);
        }
        if (key != null) {
            request = request.header("Idempotency-Key", key);
        }
        return mockMvc.perform(request).andReturn();
    }

    // ---------------------------------------------------------------- a machine can deliver

    @Test
    @DisplayName("a device with a valid token delivers a document and it is created")
    void aDeviceDelivers() throws Exception {
        Device device = device();

        MvcResult result = deliver(device, device.token(),
                "Seite eins".getBytes(StandardCharsets.UTF_8), "scan-001.png", "key-1");

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        String body = result.getResponse().getContentAsString();
        UUID documentId = UUID.fromString(JsonPath.read(body, "$.documentId"));
        assertThat(JsonPath.read(body, "$.contentHash").toString()).hasSize(64);
        assertThat(jdbc.queryForObject("""
                select count(*) from documents where id = ? and tenant_id = ?
                """, Integer.class, documentId, device.tenantId())).isEqualTo(1);
    }

    @Test
    @DisplayName("the same content delivered twice is recognised, not stored twice")
    void theSameContentIsRecognised() throws Exception {
        Device device = device();
        byte[] page = "Seite eins".getBytes(StandardCharsets.UTF_8);

        deliver(device, device.token(), page, "scan-001.png", "key-a");
        MvcResult again = deliver(device, device.token(), page, "scan-001.png", "key-b");

        assertThat(again.getResponse().getStatus())
                .as("a retried upload answers with the document that already exists")
                .isEqualTo(200);
        assertThat(JsonPath.read(again.getResponse().getContentAsString(), "$.duplicate").toString())
                .isEqualTo("true");
        assertThat(jdbc.queryForObject("""
                select count(*) from documents where tenant_id = ?
                """, Integer.class, device.tenantId())).isEqualTo(1);
    }

    @Test
    @DisplayName("the hash is computed from the content and is not taken from the caller")
    void theHashIsComputed() throws Exception {
        Device device = device();
        byte[] page = "Seite eins".getBytes(StandardCharsets.UTF_8);

        MvcResult result = deliver(device, device.token(), page, "scan.png", "key-hash");

        String hash = JsonPath.read(result.getResponse().getContentAsString(), "$.contentHash");
        assertThat(hash).isEqualTo(IngestController.sha256(page));
        assertThat(jdbc.queryForObject("""
                select content_hash from documents where tenant_id = ?
                """, String.class, device.tenantId())).isEqualTo(hash);
    }

    // ---------------------------------------------------------------- what is refused

    @Test
    @DisplayName("without a token nothing is delivered")
    void withoutATokenNothingIsDelivered() throws Exception {
        Device device = device();

        MvcResult result = deliver(device, null, "Seite".getBytes(StandardCharsets.UTF_8), "scan.png",
                "key-none");

        assertThat(result.getResponse().getStatus())
                .as("a machine interface without a token is reached by anybody who finds the address")
                .isIn(401, 403);
        assertThat(jdbc.queryForObject("select count(*) from documents where tenant_id = ?", Integer.class,
                device.tenantId())).isZero();
    }

    @Test
    @DisplayName("an unknown token is refused")
    void anUnknownTokenIsRefused() throws Exception {
        Device device = device();

        MvcResult result = deliver(device, "bc_gibt-es-nicht", "Seite".getBytes(StandardCharsets.UTF_8),
                "scan.png", "key-unknown");

        assertThat(result.getResponse().getStatus()).isIn(401, 403);
    }

    @Test
    @DisplayName("a token without the writing scope may not deliver")
    void aTokenWithoutTheScopeMayNotDeliver() throws Exception {
        Device device = device("documents:read");

        MvcResult result = deliver(device, device.token(), "Seite".getBytes(StandardCharsets.UTF_8),
                "scan.png", "key-scope");

        assertThat(result.getResponse().getStatus()).isIn(401, 403);
        assertThat(jdbc.queryForObject("select count(*) from documents where tenant_id = ?", Integer.class,
                device.tenantId())).isZero();
    }

    @Test
    @DisplayName("a delivery without a filename is refused, because it could not be found again")
    void withoutAFilenameNothingIsDelivered() throws Exception {
        Device device = device();

        MvcResult result = deliver(device, device.token(), "Seite".getBytes(StandardCharsets.UTF_8), null,
                "key-noname");

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("an empty delivery is refused")
    void anEmptyDeliveryIsRefused() throws Exception {
        Device device = device();

        MvcResult result = deliver(device, device.token(), new byte[0], "scan.png", "key-empty");

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }

    // ---------------------------------------------------------------- the shapes

    @Test
    @DisplayName("the token is read from an Authorization header, however it is spelled")
    void theTokenIsReadFromTheHeader() {
        assertThat(IngestController.bearer("Bearer abc")).isEqualTo("abc");
        assertThat(IngestController.bearer("bearer abc")).isEqualTo("abc");
        assertThat(IngestController.bearer("  Bearer   abc  ")).isEqualTo("abc");
        assertThat(IngestController.bearer("abc")).isEqualTo("abc");
        assertThat(IngestController.bearer(null)).isNull();
        assertThat(IngestController.bearer("  ")).isNull();
    }

    @Test
    @DisplayName("the hash is lower-case hexadecimal of the right length")
    void theHashHasTheShapeTheColumnExpects() {
        String hash = IngestController.sha256("x".getBytes(StandardCharsets.UTF_8));

        // The column has a constraint on this shape, so a hash in another shape fails at the database
        // rather than at the door.
        assertThat(hash).matches("^[0-9a-f]{64}$");
    }
}
