package de.buerokratiecopilot.identity.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.http.Cookie;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
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

import de.buerokratiecopilot.billing.PlanFeatures;

/**
 * HD-OIDC against a stand-in identity provider written for this test (discovery, signing keys, token endpoint, with PKCE
 * checked on its side). It proves that this platform's protocol handling is strict; it does not prove that a particular
 * vendor's provider works, which needs that provider's issuer and client and a human who owns an account there.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OidcLoginIT {

    private static final String CLIENT_ID = "copilot-test";
    private static final HttpServer IDP;
    private static final String ISSUER;
    private static final KeyPair KEY;
    private static final KeyPair OTHER_KEY;

    /** What the stand-in provider does with the next code exchange. */
    private static final AtomicReference<String> MODE = new AtomicReference<>("ok");
    private static final AtomicReference<String> EMAIL = new AtomicReference<>("");
    private static final AtomicReference<String> NONCE = new AtomicReference<>("");
    private static final AtomicReference<String> CHALLENGE = new AtomicReference<>("");
    private static volatile boolean pkceChecked;

    static {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KEY = generator.generateKeyPair();
            OTHER_KEY = generator.generateKeyPair();
            IDP = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ISSUER = "http://127.0.0.1:" + IDP.getAddress().getPort();
            IDP.createContext("/.well-known/openid-configuration", exchange -> reply(exchange, "{\"issuer\":\"" + ISSUER + "\","
                    + "\"authorization_endpoint\":\"" + ISSUER + "/authorize\",\"token_endpoint\":\"" + ISSUER + "/token\","
                    + "\"jwks_uri\":\"" + ISSUER + "/jwks\"}"));
            IDP.createContext("/jwks", exchange -> {
                RSAPublicKey pub = (RSAPublicKey) KEY.getPublic();
                reply(exchange, "{\"keys\":[{\"kty\":\"RSA\",\"use\":\"sig\",\"kid\":\"k1\",\"alg\":\"RS256\",\"n\":\""
                        + b64(unsigned(pub.getModulus())) + "\",\"e\":\"" + b64(unsigned(pub.getPublicExponent())) + "\"}]}");
            });
            IDP.createContext("/token", exchange -> {
                String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<String, String> fields = new HashMap<>();
                for (String pair : form.split("&")) {
                    String[] kv = pair.split("=", 2);
                    fields.put(kv[0], URLDecoder.decode(kv.length > 1 ? kv[1] : "", StandardCharsets.UTF_8));
                }
                try {
                    String expected = b64(MessageDigest.getInstance("SHA-256").digest(fields.getOrDefault("code_verifier", "").getBytes(StandardCharsets.US_ASCII)));
                    pkceChecked = expected.equals(CHALLENGE.get());
                }
                catch (Exception ex) {
                    throw new IOException(ex);
                }
                if (!pkceChecked || !"abc".equals(fields.get("code"))) {
                    exchange.sendResponseHeaders(400, -1);
                    exchange.close();
                    return;
                }
                try {
                    reply(exchange, "{\"id_token\":\"" + idToken(MODE.get()) + "\",\"token_type\":\"Bearer\"}");
                }
                catch (Exception ex) {
                    throw new IOException(ex);
                }
            });
            IDP.start();
        }
        catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @DynamicPropertySource
    static void provider(DynamicPropertyRegistry registry) {
        registry.add("burokratie.security.oidc.enabled", () -> "true");
        registry.add("burokratie.security.oidc.issuer-uri", () -> ISSUER);
        registry.add("burokratie.security.oidc.client-id", () -> CLIENT_ID);
        registry.add("burokratie.security.oidc.client-secret", () -> "secret");
    }

    @AfterAll
    static void stop() {
        IDP.stop(0);
    }

    private static byte[] unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        return bytes.length > 1 && bytes[0] == 0 ? java.util.Arrays.copyOfRange(bytes, 1, bytes.length) : bytes;
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String idToken(String mode) throws Exception {
        long now = Instant.now().getEpochSecond();
        String alg = "none".equals(mode) ? "none" : "RS256";
        String claims = "{\"iss\":\"" + ISSUER + "\",\"aud\":\"" + ("aud".equals(mode) ? "someone-else" : CLIENT_ID) + "\",\"sub\":\"u-1\","
                + "\"email\":\"" + EMAIL.get() + "\",\"email_verified\":" + (!"unverified".equals(mode)) + ","
                + "\"nonce\":\"" + ("nonce".equals(mode) ? "other" : NONCE.get()) + "\",\"iat\":" + now + ",\"exp\":"
                + ("expired".equals(mode) ? now - 3600 : now + 300) + "}";
        String signingInput = b64(("{\"alg\":\"" + alg + "\",\"kid\":\"k1\"}").getBytes(StandardCharsets.UTF_8)) + "." + b64(claims.getBytes(StandardCharsets.UTF_8));
        if ("none".equals(mode)) {
            return signingInput + ".";
        }
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign("otherkey".equals(mode) ? OTHER_KEY.getPrivate() : KEY.getPrivate());
        signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + b64(signature.sign());
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlanFeatures plans;

    private record Account(UUID tenantId, String email) {
    }

    private Account newTenant(int tier) throws Exception {
        String email = "sso-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult r = mockMvc.perform(post("/api/v1/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"EinSicheresPasswort-2026!\",\"displayName\":\"S\",\"accountType\":\"COMPANY\"}".formatted(email)))
                .andReturn();
        UUID tenantId = UUID.fromString(JsonPath.read(r.getResponse().getContentAsString(), "$.tenantId"));
        UUID userId = UUID.fromString(JsonPath.read(r.getResponse().getContentAsString(), "$.userId"));
        jdbc.update("update users set status = 'ACTIVE', email_verified_at = now() where id = ?", userId);
        String plan = jdbc.queryForObject("select code from plans where tier = ? order by sort_order limit 1", String.class, tier);
        jdbc.update("update subscriptions set plan_code = ? where tenant_id = ?", plan, tenantId);
        plans.forget(tenantId);
        return new Account(tenantId, email);
    }

    /** Starts the sign-in the way a browser does and reports what the provider would see. */
    private record Started(String state, Cookie cookie) {
    }

    private Started start() throws Exception {
        MvcResult started = mockMvc.perform(get("/api/v1/auth/oidc/start")).andReturn();
        assertThat(started.getResponse().getStatus()).isEqualTo(302);
        URI location = URI.create(started.getResponse().getHeader("Location"));
        assertThat(location.toString()).startsWith(ISSUER + "/authorize?");
        Map<String, String> query = new HashMap<>();
        for (String pair : location.getRawQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            query.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
        assertThat(query).containsEntry("response_type", "code").containsEntry("client_id", CLIENT_ID).containsEntry("code_challenge_method", "S256");
        assertThat(query.get("redirect_uri")).endsWith("/api/v1/auth/oidc/callback");
        NONCE.set(query.get("nonce"));
        CHALLENGE.set(query.get("code_challenge"));
        Cookie cookie = started.getResponse().getCookie("bc_oidc");
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        return new Started(query.get("state"), cookie);
    }

    private MvcResult callback(Started started, String state, String code) throws Exception {
        return mockMvc.perform(get("/api/v1/auth/oidc/callback").param("code", code).param("state", state).cookie(started.cookie())).andReturn();
    }

    private void expectRefused(MvcResult result) {
        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        assertThat(result.getResponse().getHeader("Location")).endsWith("/login?error=oidc");
        assertThat(result.getResponse().getCookie("bc_session")).as("no session on a refusal").isNull();
    }

    @Test
    @DisplayName("a verified identity of an existing account on the Professional plan signs in; the session works; PKCE was checked by the provider")
    void signsIn() throws Exception {
        Account account = newTenant(3);
        EMAIL.set(account.email());
        MODE.set("ok");
        Started started = start();

        MvcResult result = callback(started, started.state(), "abc");

        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        assertThat(result.getResponse().getHeader("Location")).doesNotContain("error");
        Cookie session = result.getResponse().getCookie("bc_session");
        assertThat(session).isNotNull();
        assertThat(pkceChecked).isTrue();
        assertThat(mockMvc.perform(get("/api/v1/auth/me").cookie(session)).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from audit_events where tenant_id = ? and action = 'LOGIN_SUCCEEDED'", Integer.class, account.tenantId()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a wrong state, a cookie that is not ours, or no cookie at all is refused")
    void stateIsChecked() throws Exception {
        Account account = newTenant(3);
        EMAIL.set(account.email());
        MODE.set("ok");
        Started started = start();

        expectRefused(callback(started, "another-state", "abc"));
        expectRefused(callback(new Started(started.state(), new Cookie("bc_oidc", started.cookie().getValue() + "x")), started.state(), "abc"));
        expectRefused(mockMvc.perform(get("/api/v1/auth/oidc/callback").param("code", "abc").param("state", started.state())).andReturn());
        expectRefused(callback(started, started.state(), "wrong-code"));
    }

    @Test
    @DisplayName("a token that is unsigned, signed by another key, for another client, expired, with another nonce or with an unverified address is refused")
    void tokenIsChecked() throws Exception {
        Account account = newTenant(3);
        EMAIL.set(account.email());
        for (String mode : new String[] { "none", "otherkey", "aud", "expired", "nonce", "unverified" }) {
            MODE.set(mode);
            Started started = start();
            expectRefused(callback(started, started.state(), "abc"));
        }
    }

    @Test
    @DisplayName("no account is created from a token; a workspace below the Professional plan is refused; both are audited")
    void accountAndPlan() throws Exception {
        MODE.set("ok");
        EMAIL.set("nobody-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de");
        Started unknown = start();
        expectRefused(callback(unknown, unknown.state(), "abc"));
        assertThat(jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, EMAIL.get())).isZero();

        Account small = newTenant(1);
        EMAIL.set(small.email());
        Started started = start();
        expectRefused(callback(started, started.state(), "abc"));
        assertThat(jdbc.queryForObject("select count(*) from audit_events where tenant_id is null or tenant_id = ? and action = 'OIDC_LOGIN_DENIED'",
                Integer.class, small.tenantId())).isGreaterThanOrEqualTo(1);
    }
}
