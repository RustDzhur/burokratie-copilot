package de.buerokratiecopilot.mail.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.http.Cookie;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
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

import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.integrations.CredentialCipher;
import de.buerokratiecopilot.mail.inbound.MailAccountService;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * The whole OAuth mail connection against a token endpoint written for this test.
 *
 * <p>What it proves: the start answer carries {@code state} and a PKCE S256 challenge and sets the signed
 * state cookie; the callback accepts only the state that belongs to that cookie and exchanges the code with
 * the verifier; the tokens are stored encrypted and bound to the mailbox; a refresh replaces them. It does
 * <strong>not</strong> prove that Microsoft or Google accept this client — that needs a registered
 * application, a tenant and a real mailbox, and the catalogue says so as
 * {@code IMPLEMENTED_NEEDS_APP_REGISTRATION}.</p>
 *
 * <p>No test here reaches the internet: the provider is a {@link HttpServer} on the loopback interface whose
 * port is injected into the configuration.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MailOAuthFlowIT {

    private static final String CLIENT_ID = "copilot-mail-test";
    private static final String CLIENT_SECRET = "not-a-real-secret";
    private static final String REDIRECT = "http://localhost:3000/api/v1/mail/oauth/microsoft/callback";
    private static final HttpServer PROVIDER;
    private static final String PROVIDER_BASE;

    private static final AtomicReference<String> CHALLENGE = new AtomicReference<>("");
    private static final AtomicReference<String> EMAIL = new AtomicReference<>("");
    private static final AtomicInteger EXCHANGES = new AtomicInteger();
    private static final AtomicInteger REFRESHES = new AtomicInteger();

    static {
        try {
            PROVIDER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            PROVIDER_BASE = "http://127.0.0.1:" + PROVIDER.getAddress().getPort();
            PROVIDER.createContext("/token", MailOAuthFlowIT::token);
            PROVIDER.createContext("/userinfo", MailOAuthFlowIT::userInfo);
            PROVIDER.start();
        }
        catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @DynamicPropertySource
    static void provider(DynamicPropertyRegistry registry) {
        registry.add("burokratie.mail.oauth.microsoft.client-id", () -> CLIENT_ID);
        registry.add("burokratie.mail.oauth.microsoft.client-secret", () -> CLIENT_SECRET);
        registry.add("burokratie.mail.oauth.microsoft.tenant-id", () -> "test-tenant");
        registry.add("burokratie.mail.oauth.microsoft.authorize-url", () -> PROVIDER_BASE + "/authorize");
        registry.add("burokratie.mail.oauth.microsoft.token-url", () -> PROVIDER_BASE + "/token");
        registry.add("burokratie.mail.oauth.microsoft.user-info-url", () -> PROVIDER_BASE + "/userinfo");
        registry.add("burokratie.mail.oauth.microsoft.scope",
                () -> "openid email profile offline_access https://outlook.office.com/IMAP.AccessAsUser.All");
        registry.add("burokratie.mail.oauth.google.client-id", () -> "google-mail-test");
        registry.add("burokratie.mail.oauth.google.client-secret", () -> "not-a-real-secret-either");
        registry.add("burokratie.mail.oauth.google.authorize-url",
                () -> PROVIDER_BASE + "/google-authorize");
        registry.add("burokratie.mail.oauth.google.token-url", () -> PROVIDER_BASE + "/token");
        registry.add("burokratie.mail.oauth.google.user-info-url", () -> PROVIDER_BASE + "/userinfo");
    }

    @AfterAll
    static void stop() {
        PROVIDER.stop(0);
    }

    private static void token(HttpExchange exchange) throws IOException {
        String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> fields = parseForm(form);
        String grant = fields.getOrDefault("grant_type", "");
        if ("authorization_code".equals(grant)) {
            boolean pkce = CHALLENGE.get() != null && CHALLENGE.get().equals(s256(fields.get("code_verifier")));
            boolean valid = "good-code".equals(fields.get("code"))
                    && CLIENT_ID.equals(fields.get("client_id"))
                    && CLIENT_SECRET.equals(fields.get("client_secret"))
                    && REDIRECT.equals(fields.get("redirect_uri"))
                    && pkce;
            if (!valid) {
                error(exchange, "invalid_grant");
                return;
            }
            EXCHANGES.incrementAndGet();
            json(exchange, "{\"access_token\":\"access-one\",\"refresh_token\":\"refresh-one\","
                    + "\"expires_in\":3600,\"token_type\":\"Bearer\",\"scope\":\"openid email\"}");
            return;
        }
        if ("refresh_token".equals(grant)) {
            if (!"refresh-one".equals(fields.get("refresh_token"))) {
                error(exchange, "invalid_grant");
                return;
            }
            REFRESHES.incrementAndGet();
            json(exchange, "{\"access_token\":\"access-two\",\"refresh_token\":\"refresh-two\","
                    + "\"expires_in\":3600,\"token_type\":\"Bearer\"}");
            return;
        }
        error(exchange, "unsupported_grant_type");
    }

    private static void userInfo(HttpExchange exchange) throws IOException {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer access-")) {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
            return;
        }
        json(exchange, "{\"mail\":\"" + EMAIL.get() + "\"}");
    }

    private static void json(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void error(HttpExchange exchange, String code) throws IOException {
        byte[] bytes = ("{\"error\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(400, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static Map<String, String> parseForm(String form) {
        Map<String, String> fields = new HashMap<>();
        for (String pair : form.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            String[] kv = pair.split("=", 2);
            fields.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(kv.length > 1 ? kv[1] : "", StandardCharsets.UTF_8));
        }
        return fields;
    }

    private static String s256(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(
                            (verifier == null ? "" : verifier).getBytes(StandardCharsets.US_ASCII)));
        }
        catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private UserRepository users;
    @Autowired
    private CredentialCipher cipher;
    @Autowired
    private MailAccountService accounts;
    @Autowired
    private MailOAuthTokenService tokens;

    private record Session(Cookie cookie, UUID tenantId, UUID userId) {
    }

    @BeforeEach
    void reset() {
        CHALLENGE.set("");
        EMAIL.set("oauth-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de");
        EXCHANGES.set(0);
        REFRESHES.set(0);
    }

    private Session register() throws Exception {
        String email = "person-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"OAuth Test","accountType":"COMPANY"}
                                """.formatted(email)))
                .andReturn();
        UUID tenantId = UUID.fromString(JsonPath.read(
                registration.getResponse().getContentAsString(), "$.tenantId"));
        UUID userId = UUID.fromString(JsonPath.read(
                registration.getResponse().getContentAsString(), "$.userId"));
        RequestContext.runAs(tenantId, () -> {
            User user = users.findById(userId).orElseThrow();
            user.markEmailVerified(Instant.now());
            user.setStatus(User.Status.ACTIVE);
            return users.save(user);
        });
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!"}
                                """.formatted(email)))
                .andReturn();
        return new Session(login.getResponse().getCookie("bc_session"), tenantId, userId);
    }

    private record Started(String state, String challenge, Cookie cookie, URI location) {
    }

    private Started start(Session session) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/mail/oauth/microsoft/start")
                        .cookie(session.cookie()))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        URI location = URI.create(result.getResponse().getHeader("Location"));
        Map<String, String> query = parseForm(location.getRawQuery());
        CHALLENGE.set(query.get("code_challenge"));
        Cookie cookie = result.getResponse().getCookie("bc_mail_oauth_microsoft");
        assertThat(cookie).as("the state cookie must be set").isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/api/v1/mail/oauth");
        return new Started(query.get("state"), query.get("code_challenge"), cookie, location);
    }

    private MvcResult callback(Started started, String code, String state, Cookie cookie) throws Exception {
        return mockMvc.perform(get("/api/v1/mail/oauth/microsoft/callback")
                        .queryParam("code", code)
                        .queryParam("state", state)
                        .cookie(cookie))
                .andReturn();
    }

    private Map<String, Object> account(UUID tenantId) {
        return jdbc.queryForMap("""
                select id, provider, status, email_address, credential_encrypted
                from mail_accounts where tenant_id = ?
                """, tenantId);
    }

    private Map<String, String> storedTokens(UUID tenantId, UUID accountId) {
        String stored = jdbc.queryForObject("select credential_encrypted from mail_accounts where id = ?",
                String.class, accountId);
        return cipher.decryptFields(stored, tenantId, accountId);
    }

    private Started connect(Session session) throws Exception {
        Started started = start(session);
        MvcResult finished = callback(started, "good-code", started.state(), started.cookie());
        assertThat(finished.getResponse().getStatus()).isEqualTo(302);
        assertThat(finished.getResponse().getHeader("Location"))
                .isEqualTo("http://localhost:3000/mail?oauth=connected&provider=microsoft");
        return started;
    }

    @Test
    @DisplayName("start sends the browser to the provider with state and a PKCE S256 challenge")
    void startCarriesStateAndPkce() throws Exception {
        Session session = register();

        Started started = start(session);

        assertThat(started.location().toString()).startsWith(PROVIDER_BASE + "/authorize?");
        Map<String, String> query = parseForm(started.location().getRawQuery());
        assertThat(query).containsEntry("response_type", "code")
                .containsEntry("client_id", CLIENT_ID)
                .containsEntry("redirect_uri", REDIRECT)
                .containsEntry("code_challenge_method", "S256")
                .containsEntry("state", started.state());
        assertThat(query.get("state")).isNotBlank();
        assertThat(query.get("code_challenge")).isNotBlank();
        assertThat(started.challenge()).isEqualTo(query.get("code_challenge"));
        assertThat(EXCHANGES.get()).as("start must not exchange anything yet").isZero();
    }

    @Test
    @DisplayName("the callback exchanges the code, checks PKCE and stores the tokens encrypted")
    void callbackStoresEncryptedTokens() throws Exception {
        Session session = register();
        Started started = start(session);

        MvcResult finished = callback(started, "good-code", started.state(), started.cookie());

        assertThat(finished.getResponse().getStatus()).isEqualTo(302);
        assertThat(finished.getResponse().getHeader("Location"))
                .isEqualTo("http://localhost:3000/mail?oauth=connected&provider=microsoft");
        Cookie cleared = finished.getResponse().getCookie("bc_mail_oauth_microsoft");
        assertThat(cleared).isNotNull();
        assertThat(cleared.getMaxAge()).as("the one-time state cookie is cleared").isZero();
        assertThat(EXCHANGES.get()).isEqualTo(1);

        Map<String, Object> account = account(session.tenantId());
        assertThat(account).containsEntry("provider", "MICROSOFT365")
                .containsEntry("status", "DISCONNECTED");
        assertThat(String.valueOf(account.get("email_address"))).isEqualTo(EMAIL.get());
        UUID accountId = (UUID) account.get("id");

        String stored = String.valueOf(account.get("credential_encrypted"));
        assertThat(stored).startsWith("v1.");
        assertThat(stored).doesNotContain("access-one").doesNotContain("refresh-one");
        Map<String, String> fields = storedTokens(session.tenantId(), accountId);
        assertThat(fields).containsEntry(MailAccountService.ACCESS_TOKEN, "access-one")
                .containsEntry(MailAccountService.REFRESH_TOKEN, "refresh-one")
                .containsEntry(MailAccountService.TOKEN_TYPE, "Bearer");
        assertThat(Instant.parse(fields.get(MailAccountService.EXPIRES_AT)))
                .as("the expiry comes from the provider's expires_in")
                .isAfter(Instant.now().plusSeconds(3000));
    }

    @Test
    @DisplayName("a forged state is refused and nothing is stored")
    void forgedStateIsRefused() throws Exception {
        Session session = register();
        Started started = start(session);

        MvcResult refused = callback(started, "good-code", "forged-state", started.cookie());

        assertThat(refused.getResponse().getStatus()).isEqualTo(302);
        assertThat(refused.getResponse().getHeader("Location"))
                .isEqualTo("http://localhost:3000/mail?oauth=error&reason=state");
        assertThat(EXCHANGES.get()).as("no code may be exchanged for a state we did not issue").isZero();
        assertThat(jdbc.queryForObject("select count(*) from mail_accounts where tenant_id = ?",
                Integer.class, session.tenantId())).isZero();
    }

    @Test
    @DisplayName("a callback without the state cookie is refused")
    void missingStateCookieIsRefused() throws Exception {
        Session session = register();
        Started started = start(session);

        MvcResult refused = mockMvc.perform(get("/api/v1/mail/oauth/microsoft/callback")
                        .queryParam("code", "good-code")
                        .queryParam("state", started.state()))
                .andReturn();

        assertThat(refused.getResponse().getHeader("Location"))
                .isEqualTo("http://localhost:3000/mail?oauth=error&reason=state");
        assertThat(EXCHANGES.get()).isZero();
    }

    @Test
    @DisplayName("the state cookie of one provider does not complete another provider's flow")
    void stateCookieCannotBeUsedForAnotherProvider() throws Exception {
        Session session = register();
        Started started = start(session);

        MvcResult refused = mockMvc.perform(get("/api/v1/mail/oauth/google/callback")
                        .queryParam("code", "good-code")
                        .queryParam("state", started.state())
                        .cookie(started.cookie()))
                .andReturn();

        assertThat(refused.getResponse().getHeader("Location"))
                .isEqualTo("http://localhost:3000/mail?oauth=error&reason=state");
        assertThat(EXCHANGES.get()).isZero();
    }

    @Test
    @DisplayName("a refusal at the provider is answered as 'denied', without a token request")
    void providerRefusalIsReported() throws Exception {
        Session session = register();
        Started started = start(session);

        MvcResult refused = mockMvc.perform(get("/api/v1/mail/oauth/microsoft/callback")
                        .queryParam("error", "access_denied")
                        .queryParam("state", started.state())
                        .cookie(started.cookie()))
                .andReturn();

        assertThat(refused.getResponse().getHeader("Location"))
                .isEqualTo("http://localhost:3000/mail?oauth=error&reason=denied");
        assertThat(EXCHANGES.get()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from mail_accounts where tenant_id = ?",
                Integer.class, session.tenantId())).isZero();
    }

    @Test
    @DisplayName("a token that expired is refreshed before it is used, and the stored bundle is replaced")
    void expiredTokenIsRefreshed() throws Exception {
        Session session = register();
        connect(session);
        UUID accountId = (UUID) account(session.tenantId()).get("id");

        Map<String, String> fields = new java.util.LinkedHashMap<>(
                storedTokens(session.tenantId(), accountId));
        fields.put(MailAccountService.EXPIRES_AT, Instant.now().minusSeconds(60).toString());
        accounts.storeOAuthTokens(session.tenantId(), accountId, fields);

        String token = tokens.accessToken(session.tenantId(), accountId);

        assertThat(token).isEqualTo("access-two");
        assertThat(REFRESHES.get()).isEqualTo(1);
        Map<String, String> refreshed = storedTokens(session.tenantId(), accountId);
        assertThat(refreshed).containsEntry(MailAccountService.ACCESS_TOKEN, "access-two")
                .containsEntry(MailAccountService.REFRESH_TOKEN, "refresh-two");
        assertThat(Instant.parse(refreshed.get(MailAccountService.EXPIRES_AT))).isAfter(Instant.now());
    }

    @Test
    @DisplayName("a rejected token is refreshed once even though its recorded expiry has not passed")
    void rejectedTokenIsRefreshedOnce() throws Exception {
        Session session = register();
        connect(session);
        UUID accountId = (UUID) account(session.tenantId()).get("id");

        String renewed = tokens.refreshAfterUnauthorized(session.tenantId(), accountId);

        assertThat(renewed).isEqualTo("access-two");
        assertThat(REFRESHES.get()).isEqualTo(1);
        assertThat(storedTokens(session.tenantId(), accountId))
                .containsEntry(MailAccountService.ACCESS_TOKEN, "access-two");
    }

    @Test
    @DisplayName("when the refresh token is refused the mailbox needs a person, and says so")
    void refusedRefreshMarksTheMailboxForReconnection() throws Exception {
        Session session = register();
        connect(session);
        UUID accountId = (UUID) account(session.tenantId()).get("id");

        Map<String, String> fields = new java.util.LinkedHashMap<>(
                storedTokens(session.tenantId(), accountId));
        fields.put(MailAccountService.REFRESH_TOKEN, "dead-refresh-token");
        fields.put(MailAccountService.EXPIRES_AT, Instant.now().minusSeconds(60).toString());
        accounts.storeOAuthTokens(session.tenantId(), accountId, fields);

        assertThatThrownBy(() -> tokens.accessToken(session.tenantId(), accountId))
                .isInstanceOf(de.buerokratiecopilot.shared.error.ApiException.class)
                .hasMessageContaining("erneut verbinden");

        Map<String, Object> account = jdbc.queryForMap("""
                select status, last_sync_error from mail_accounts where id = ?
                """, accountId);
        assertThat(account).containsEntry("status", "AUTH_REQUIRED");
        assertThat(String.valueOf(account.get("last_sync_error"))).contains("abgelaufen");
        assertThat(jdbc.queryForObject("select count(*) from mail_accounts where id = ?", Integer.class,
                accountId)).isEqualTo(1);
    }

    @Test
    @DisplayName("the Google start asks for offline access and consents, which is what a refresh token needs")
    void googleStartRequestsOfflineAccess() throws Exception {
        Session session = register();

        MvcResult result = mockMvc.perform(get("/api/v1/mail/oauth/google/start")
                        .cookie(session.cookie()))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        URI location = URI.create(result.getResponse().getHeader("Location"));
        Map<String, String> query = parseForm(location.getRawQuery());
        assertThat(query).containsEntry("access_type", "offline")
                .containsEntry("prompt", "consent")
                .containsEntry("code_challenge_method", "S256")
                .containsEntry("redirect_uri",
                        "http://localhost:3000/api/v1/mail/oauth/google/callback");
        assertThat(query.get("scope")).contains("https://mail.google.com/");
        assertThat(result.getResponse().getCookie("bc_mail_oauth_google")).isNotNull();
    }
}
