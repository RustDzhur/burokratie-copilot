package de.buerokratiecopilot.ingest.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import de.buerokratiecopilot.ingest.IngestService;
import de.buerokratiecopilot.ingest.WebhookSignature;

/**
 * Issuing, listing and revoking API tokens from the settings screen (spec §7.4, B-013).
 *
 * <p>The service side is covered by {@code IngestApiIT}; what these tests add is the door. Three
 * questions decide whether the feature is safe, and each is asked by doing the thing rather than by
 * reading the code:</p>
 *
 * <ol>
 *   <li><strong>Is the plaintext shown exactly once?</strong> It is created and then looked for in the
 *       list, in the database and in the log — a credential that can be read again is not a credential
 *       that was shown once.</li>
 *   <li><strong>Does the token work where it claims to?</strong> The freshly issued value is used to
 *       deliver a document over {@code POST /documents:ingest}.</li>
 *   <li><strong>Can somebody without the permission manage tokens?</strong> A member with the
 *       employee role tries to list, create and revoke, and is refused.</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiTokenControllerIT {

    private static final String PASSWORD = "EinSicheresPasswort-2026!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private record Account(UUID tenantId, UUID userId, String email, Cookie cookie) {
    }

    // ------------------------------------------------------------------ fixtures

    /** Registers an account; registration makes it the OWNER, who holds every permission. */
    private Account owner() throws Exception {
        String email = "tokens-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registered = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"email\":\"%s\",\"password\":\"%s\",\"displayName\":\"Owner\","
                                + "\"accountType\":\"COMPANY\"}").formatted(email, PASSWORD)))
                .andReturn();
        assertThat(registered.getResponse().getStatus()).isEqualTo(201);
        UUID tenantId = UUID.fromString(JsonPath.read(content(registered), "$.tenantId"));
        UUID userId = UUID.fromString(JsonPath.read(content(registered), "$.userId"));
        jdbc.update("update users set status = 'ACTIVE', email_verified_at = now() where id = ?", userId);
        return new Account(tenantId, userId, email, login(email));
    }

    /**
     * A second person in the same tenant with the EMPLOYEE role.
     *
     * <p>The password hash is copied instead of recomputed: the question is whether the employee may
     * manage tokens, not how passwords are hashed. EMPLOYEE deliberately has no api_tokens.manage.</p>
     */
    private Account employeeOf(Account owner) throws Exception {
        String email = "employee-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        UUID userId = jdbc.queryForObject("""
                insert into users (id, email, status, display_name, locale, time_zone,
                                   failed_login_attempts, must_change_password, email_verified_at,
                                   password_hash)
                select gen_random_uuid(), ?, 'ACTIVE', 'Employee', 'de', 'Europe/Berlin', 0, false,
                       now(), password_hash
                from users where id = ?
                returning id
                """, UUID.class, email, owner.userId());
        UUID membershipId = jdbc.queryForObject("""
                insert into memberships (id, tenant_id, user_id, status, is_owner)
                values (gen_random_uuid(), ?, ?, 'ACTIVE', false)
                returning id
                """, UUID.class, owner.tenantId(), userId);
        jdbc.update("""
                insert into membership_roles (membership_id, role_id, granted_at)
                select ?, id, now() from roles where tenant_id is null and code = 'EMPLOYEE'
                """, membershipId);
        return new Account(owner.tenantId(), userId, email, login(email));
    }

    private Cookie login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("sign-in of %s", email).isEqualTo(200);
        Cookie cookie = result.getResponse().getCookie("bc_session");
        assertThat(cookie).as("session cookie").isNotNull();
        return cookie;
    }

    private UUID defaultWorkspace(UUID tenantId) {
        return jdbc.queryForObject("""
                select id from workspaces where tenant_id = ? and is_default and deleted_at is null
                """, UUID.class, tenantId);
    }

    // ------------------------------------------------------------------ calls

    private MvcResult list(Account account) throws Exception {
        return mockMvc.perform(get("/api/v1/api-tokens").cookie(account.cookie())).andReturn();
    }

    private MvcResult create(Account account, String name, String scopesJson) throws Exception {
        return create(account, name, scopesJson, null);
    }

    private MvcResult create(Account account, String name, String scopesJson, String idempotencyKey)
            throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/api-tokens").with(csrf())
                .cookie(account.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":%s,\"scopes\":%s}".formatted(
                        name == null ? "null" : "\"" + name + "\"", scopesJson));
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return mockMvc.perform(request).andReturn();
    }

    private MvcResult revoke(Account account, String tokenId) throws Exception {
        return mockMvc.perform(post("/api/v1/api-tokens/" + tokenId + "/revoke").with(csrf())
                .cookie(account.cookie())).andReturn();
    }

    private MvcResult deliver(String token, byte[] content, String filename, String key) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/documents:ingest")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(content)
                .header("X-Filename", filename)
                .header("Idempotency-Key", key);
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return mockMvc.perform(request).andReturn();
    }

    private static String content(MvcResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private static void assertNoStore(MvcResult result) {
        assertThat(result.getResponse().getHeader("Cache-Control"))
                .as("a response that carries a credential must not be cached")
                .isNotNull()
                .contains("no-store");
    }

    // ------------------------------------------------------------------ the plaintext exists once

    @Test
    @DisplayName("a token is returned once, stored as a hash, and never appears in the list again")
    void tokenShownOnceStoredAsHash() throws Exception {
        Account account = owner();

        MvcResult created = create(account, "Desktop Bridge", "[\"documents:write\"]");

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        assertNoStore(created);
        String body = content(created);
        String token = JsonPath.read(body, "$.token");
        String id = JsonPath.read(body, "$.id");
        assertThat(token).startsWith("bc_").hasSizeGreaterThan(20);
        assertThat((String) JsonPath.read(body, "$.prefix")).isEqualTo(token.substring(0, 12));
        assertThat((List<String>) JsonPath.read(body, "$.scopes")).containsExactly("documents:write");
        assertThat((String) JsonPath.read(body, "$.warning")).isNotBlank();
        assertThat(body).contains(token);

        String stored = jdbc.queryForObject("select token_hash from api_tokens where id = ?::uuid",
                String.class, id);
        assertThat(stored).isEqualTo(WebhookSignature.hashApiToken(token)).isNotEqualTo(token);
        UUID workspaceId = jdbc.queryForObject("select workspace_id from api_tokens where id = ?::uuid",
                UUID.class, id);
        assertThat(workspaceId).isEqualTo(defaultWorkspace(account.tenantId()));

        MvcResult listed = list(account);
        assertThat(listed.getResponse().getStatus()).isEqualTo(200);
        assertNoStore(listed);
        assertThat(content(listed))
                .as("the list must not contain the plaintext or the hash")
                .doesNotContain(token)
                .doesNotContain(stored)
                .doesNotContain("tokenHash")
                .doesNotContain("token_hash");
    }

    @Test
    @DisplayName("an idempotency key cannot put a credential into the replay store")
    void credentialIsNeverStoredForReplay() throws Exception {
        Account account = owner();

        MvcResult first = create(account, "Bridge", "[\"documents:write\"]", "token-key-1");
        MvcResult second = create(account, "Bridge", "[\"documents:write\"]", "token-key-1");

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getStatus())
                .as("a reply that carries a credential is never replayed from the store")
                .isEqualTo(201);
        assertThat(content(first)).isNotEqualTo(content(second));
        assertThat(jdbc.queryForObject("""
                select count(*) from idempotency_records where tenant_id = ?
                """, Integer.class, account.tenantId()))
                .as("no replay row may hold the plaintext token")
                .isZero();
    }

    @Test
    @DisplayName("the token never reaches the log; only its identifier and prefix do")
    void tokenIsNotLogged() throws Exception {
        Account account = owner();
        Logger logger = (Logger) LoggerFactory.getLogger(IngestService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            String token = JsonPath.read(content(create(account, "Logged", "[\"documents:write\"]")),
                    "$.token");

            List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(messages).isNotEmpty();
            assertThat(messages).allSatisfy(message -> assertThat(message).doesNotContain(token));
        }
        finally {
            logger.detachAppender(appender);
        }
    }

    // ------------------------------------------------------------------ listing

    @Test
    @DisplayName("the list names every token of the account, newest first, with the scope catalogue")
    void listsTokens() throws Exception {
        Account account = owner();
        create(account, "Erster", "[\"documents:write\"]");
        create(account, "Zweiter", "[\"documents:write\",\"agents.view\"]");

        MvcResult listed = list(account);

        assertThat(listed.getResponse().getStatus()).isEqualTo(200);
        String body = content(listed);
        assertThat((Integer) JsonPath.read(body, "$.count")).isEqualTo(2);
        List<String> names = JsonPath.read(body, "$.items[*].name");
        assertThat(names).containsExactly("Zweiter", "Erster");
        List<String> prefixes = JsonPath.read(body, "$.items[*].prefix");
        assertThat(prefixes).allSatisfy(prefix -> assertThat(prefix).startsWith("bc_"));
        List<String> ids = JsonPath.read(body, "$.items[*].id");
        assertThat(ids).allSatisfy(id -> assertThat(UUID.fromString(id)).isNotNull());
        List<String> createdAts = JsonPath.read(body, "$.items[*].createdAt");
        assertThat(createdAts).allSatisfy(value -> assertThat(value).isNotBlank());
        List<List<String>> scopes = JsonPath.read(body, "$.items[*].scopes");
        assertThat(scopes).containsExactly(List.of("documents:write", "agents.view"),
                List.of("documents:write"));
        assertThat((List<String>) JsonPath.read(body, "$.availableScopes"))
                .contains("documents:write", "documents:read", "agents.view", "agents.run");
        assertThat(body).doesNotContain("\"token\":");
    }

    // ------------------------------------------------------------------ the token works

    @Test
    @DisplayName("the freshly issued token delivers a document through POST /documents:ingest")
    void issuedTokenDeliversADocument() throws Exception {
        Account account = owner();
        String token = JsonPath.read(content(create(account, "Partner", "[\"documents:write\"]")),
                "$.token");

        MvcResult delivered = deliver(token, "Rechnung über 119 Euro".getBytes(StandardCharsets.UTF_8),
                "rechnung-4711.pdf", "key-4711");

        assertThat(delivered.getResponse().getStatus()).isEqualTo(201);
        String documentId = JsonPath.read(content(delivered), "$.documentId");
        assertThat(jdbc.queryForObject("""
                select count(*) from documents where id = ?::uuid and tenant_id = ?
                """, Integer.class, documentId, account.tenantId())).isEqualTo(1);
        // The delivery is attributed to the token: last_used_at is what the list shows.
        assertThat(jdbc.queryForObject("""
                select last_used_at is not null from api_tokens where name = 'Partner' and tenant_id = ?
                """, Boolean.class, account.tenantId())).isTrue();
    }

    @Test
    @DisplayName("a token without the writing scope may not deliver")
    void aReadOnlyTokenDoesNotWrite() throws Exception {
        Account account = owner();
        String token = JsonPath.read(content(create(account, "Leser", "[\"documents:read\"]")),
                "$.token");

        MvcResult delivered = deliver(token, "Seite".getBytes(StandardCharsets.UTF_8), "seite.pdf",
                "key-read-only");

        assertThat(delivered.getResponse().getStatus()).isIn(401, 403);
        assertThat(jdbc.queryForObject("select count(*) from documents where tenant_id = ?",
                Integer.class, account.tenantId())).isZero();
    }

    // ------------------------------------------------------------------ revoking

    @Test
    @DisplayName("revoking stops the token, keeps the row visible, and revoking twice is not an error")
    void revokingStopsTheToken() throws Exception {
        Account account = owner();
        String created = content(create(account, "Zu widerrufen", "[\"documents:write\"]"));
        String token = JsonPath.read(created, "$.token");
        String id = JsonPath.read(created, "$.id");

        MvcResult revoked = revoke(account, id);

        assertThat(revoked.getResponse().getStatus()).isEqualTo(204);
        assertNoStore(revoked);

        String body = content(list(account));
        assertThat((String) JsonPath.read(body, "$.items[0].id")).isEqualTo(id);
        assertThat((String) JsonPath.read(body, "$.items[0].revokedAt")).isNotBlank();
        assertThat((Boolean) JsonPath.read(body, "$.items[0].active")).isFalse();

        MvcResult delivered = deliver(token, "Seite".getBytes(StandardCharsets.UTF_8), "seite.pdf",
                "key-revoked");
        assertThat(delivered.getResponse().getStatus()).isIn(401, 403);
        assertThat(jdbc.queryForObject("select count(*) from documents where tenant_id = ?",
                Integer.class, account.tenantId())).isZero();

        assertThat(revoke(account, id).getResponse().getStatus())
                .as("the state is already what the caller asked for")
                .isEqualTo(204);
        assertThat(revoke(account, UUID.randomUUID().toString()).getResponse().getStatus())
                .as("an unknown token is not found")
                .isEqualTo(404);
    }

    // ------------------------------------------------------------------ permissions

    @Test
    @DisplayName("a member without api_tokens.manage can neither list nor create nor revoke")
    void thePermissionGuardsTheEndpoints() throws Exception {
        Account owner = owner();
        String id = JsonPath.read(content(create(owner, "Owner-Token", "[\"documents:write\"]")), "$.id");
        Account employee = employeeOf(owner);

        assertThat(list(employee).getResponse().getStatus()).isEqualTo(403);
        assertThat(create(employee, "Fremd", "[\"documents:write\"]").getResponse().getStatus())
                .isEqualTo(403);
        assertThat(revoke(employee, id).getResponse().getStatus()).isEqualTo(403);

        // The attempt changed nothing: the owner's token is still active.
        assertThat(jdbc.queryForObject("""
                select revoked_at is null from api_tokens where id = ?::uuid
                """, Boolean.class, id)).isTrue();
        assertThat(list(owner).getResponse().getStatus()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ validation and limits

    @Test
    @DisplayName("unknown scopes, no scopes and a missing name are refused; the dot spelling is accepted")
    void validation() throws Exception {
        Account account = owner();

        MvcResult unknown = create(account, "Kaputt", "[\"documents:everything\"]");
        assertThat(unknown.getResponse().getStatus()).isEqualTo(422);
        assertThat(content(unknown)).contains("scopes");

        assertThat(create(account, "Ohne Scope", "[]").getResponse().getStatus()).isEqualTo(422);
        assertThat(create(account, null, "[\"documents:write\"]").getResponse().getStatus())
                .isEqualTo(400);
        assertThat(create(account, "  ", "[\"documents:write\"]").getResponse().getStatus())
                .isEqualTo(400);

        MvcResult dotted = create(account, "Alter Absender", "[\"documents.write\"]");
        assertThat(dotted.getResponse().getStatus()).isEqualTo(201);
        assertThat((List<String>) JsonPath.read(content(dotted), "$.scopes"))
                .as("the dot spelling is stored as the colon spelling the platform checks")
                .containsExactly("documents:write");
        assertThat(jdbc.queryForObject("select count(*) from api_tokens where tenant_id = ?",
                Integer.class, account.tenantId())).isEqualTo(1);
    }

    @Test
    @DisplayName("a workspace holds at most 20 active tokens; revoking one frees the place")
    void tokenBudget() throws Exception {
        Account account = owner();
        UUID workspaceId = defaultWorkspace(account.tenantId());
        create(account, "Nummer 1", "[\"documents:write\"]");
        for (int index = 2; index <= IngestService.MAX_ACTIVE_TOKENS_PER_WORKSPACE; index++) {
            seedActiveToken(account.tenantId(), workspaceId, "Seed " + index);
        }

        MvcResult refused = create(account, "Einer zu viel", "[\"documents:write\"]");
        assertThat(refused.getResponse().getStatus())
                .as("an unbounded number of credentials is one nobody reviews")
                .isEqualTo(409);

        UUID victim = jdbc.queryForObject("""
                select id from api_tokens where tenant_id = ? and revoked_at is null order by created_at limit 1
                """, UUID.class, account.tenantId());
        assertThat(revoke(account, victim.toString()).getResponse().getStatus()).isEqualTo(204);

        assertThat(create(account, "Wieder frei", "[\"documents:write\"]").getResponse().getStatus())
                .isEqualTo(201);
    }

    private void seedActiveToken(UUID tenantId, UUID workspaceId, String name) {
        jdbc.update("""
                insert into api_tokens (id, tenant_id, workspace_id, name, token_prefix, token_hash,
                                        scopes, rate_limit_per_minute, is_active)
                values (gen_random_uuid(), ?, ?, ?, 'bc_seeded', ?, '["documents:write"]'::jsonb, 60, true)
                """, tenantId, workspaceId, name,
                WebhookSignature.hashApiToken(UUID.randomUUID().toString()));
    }

    // ------------------------------------------------------------------ isolation

    @Test
    @DisplayName("another tenant sees its own tokens only and cannot revoke a foreign one")
    void tenantsAreIsolated() throws Exception {
        Account owner = owner();
        Account other = owner();
        String id = JsonPath.read(content(create(owner, "Meins", "[\"documents:write\"]")), "$.id");

        MvcResult listed = list(other);
        assertThat(listed.getResponse().getStatus()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(content(listed), "$.count")).isZero();
        assertThat(revoke(other, id).getResponse().getStatus()).isEqualTo(404);
        assertThat(jdbc.queryForObject("""
                select revoked_at is null from api_tokens where id = ?::uuid
                """, Boolean.class, id)).isTrue();
    }
}
