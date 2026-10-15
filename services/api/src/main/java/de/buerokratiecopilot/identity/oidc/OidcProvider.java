package de.buerokratiecopilot.identity.oidc;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

import de.buerokratiecopilot.shared.config.BurokratieProperties;

/**
 * The conversation with an OpenID Connect provider: discovery, the exchange of the code, and the check of the ID token.
 *
 * <p>Only what the authorisation-code flow with PKCE needs, written against the standard and the JDK. The token is accepted
 * only if it is signed with RS256 by a key the provider publishes, was issued by the configured issuer for this client,
 * has not expired, carries the nonce that was sent, and names an e-mail address the provider says it has verified. Every
 * other outcome is a refusal; there is no "lenient" mode.</p>
 */
@Component
public class OidcProvider {

    /** What a verified ID token tells us. */
    public record Identity(String subject, String email) {
    }

    /** Raised for any reason the sign-in must not go on; the message is for the log, never for the browser. */
    public static class OidcException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public OidcException(String message) {
            super(message);
        }

        public OidcException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private record Discovery(String authorizationEndpoint, String tokenEndpoint, String jwksUri, Instant loadedAt) {
    }

    private static final Duration CACHE = Duration.ofMinutes(10);
    private static final long SKEW_SECONDS = 60;

    private final BurokratieProperties properties;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final Map<String, JsonNode> keys = new ConcurrentHashMap<>();
    private volatile Discovery discovery;
    private volatile Instant keysLoadedAt = Instant.EPOCH;

    public OidcProvider(BurokratieProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
    }

    private BurokratieProperties.Security.Oidc settings() {
        return properties.getSecurity().getOidc();
    }

    public boolean enabled() {
        BurokratieProperties.Security.Oidc oidc = settings();
        return oidc.isEnabled() && !oidc.getIssuerUri().isBlank() && !oidc.getClientId().isBlank();
    }

    private JsonNode get(String url) {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new OidcException("GET " + url + " answered " + response.statusCode());
            }
            return json.readTree(response.body());
        }
        catch (IOException ex) {
            throw new OidcException("GET " + url + " failed", ex);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new OidcException("GET " + url + " was interrupted", ex);
        }
    }

    private Discovery discovery() {
        Discovery current = discovery;
        if (current != null && current.loadedAt().plus(CACHE).isAfter(Instant.now())) {
            return current;
        }
        String issuer = settings().getIssuerUri().replaceAll("/+$", "");
        JsonNode doc = get(issuer + "/.well-known/openid-configuration");
        // The document must be about the issuer we asked for; otherwise someone else's endpoints would be trusted.
        if (!issuer.equals(doc.path("issuer").asText("").replaceAll("/+$", ""))) {
            throw new OidcException("The discovery document names another issuer");
        }
        Discovery loaded = new Discovery(doc.path("authorization_endpoint").asText(""), doc.path("token_endpoint").asText(""),
                doc.path("jwks_uri").asText(""), Instant.now());
        if (loaded.authorizationEndpoint().isBlank() || loaded.tokenEndpoint().isBlank() || loaded.jwksUri().isBlank()) {
            throw new OidcException("The discovery document is incomplete");
        }
        discovery = loaded;
        return loaded;
    }

    /** Where the browser is sent to sign in. */
    public URI authorizationUri(String redirectUri, String state, String nonce, String codeChallenge) {
        BurokratieProperties.Security.Oidc oidc = settings();
        String query = "response_type=code&client_id=" + enc(oidc.getClientId()) + "&redirect_uri=" + enc(redirectUri)
                + "&scope=" + enc(oidc.getScopes()) + "&state=" + enc(state) + "&nonce=" + enc(nonce)
                + "&code_challenge=" + enc(codeChallenge) + "&code_challenge_method=S256";
        String endpoint = discovery().authorizationEndpoint();
        return URI.create(endpoint + (endpoint.contains("?") ? "&" : "?") + query);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Exchanges the code for the ID token and verifies it. */
    public Identity redeem(String code, String redirectUri, String codeVerifier, String expectedNonce) {
        BurokratieProperties.Security.Oidc oidc = settings();
        String form = "grant_type=authorization_code&code=" + enc(code) + "&redirect_uri=" + enc(redirectUri)
                + "&client_id=" + enc(oidc.getClientId()) + "&code_verifier=" + enc(codeVerifier)
                + (oidc.getClientSecret().isBlank() ? "" : "&client_secret=" + enc(oidc.getClientSecret()));
        JsonNode token;
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(discovery().tokenEndpoint()))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new OidcException("The token endpoint answered " + response.statusCode());
            }
            token = json.readTree(response.body());
        }
        catch (IOException ex) {
            throw new OidcException("The token request failed", ex);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new OidcException("The token request was interrupted", ex);
        }
        String idToken = token.path("id_token").asText("");
        if (idToken.isBlank()) {
            throw new OidcException("The provider returned no ID token");
        }
        return verify(idToken, expectedNonce, Instant.now());
    }

    Identity verify(String idToken, String expectedNonce, Instant now) {
        String[] parts = idToken.split("\\.", -1);
        if (parts.length != 3) {
            throw new OidcException("The ID token is not a signed token");
        }
        JsonNode header = parse(parts[0]);
        JsonNode claims = parse(parts[1]);
        if (!"RS256".equals(header.path("alg").asText())) {
            throw new OidcException("Only RS256 is accepted, got " + header.path("alg").asText("none"));
        }
        PublicKey key = keyFor(header.path("kid").asText(""));
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(key);
            signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!signature.verify(Base64.getUrlDecoder().decode(parts[2]))) {
                throw new OidcException("The signature of the ID token is wrong");
            }
        }
        catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new OidcException("The signature of the ID token cannot be checked", ex);
        }
        String issuer = settings().getIssuerUri().replaceAll("/+$", "");
        if (!issuer.equals(claims.path("iss").asText("").replaceAll("/+$", ""))) {
            throw new OidcException("The ID token comes from another issuer");
        }
        JsonNode audience = claims.path("aud");
        String clientId = settings().getClientId();
        boolean audienceOk = audience.isArray()
                ? streamOf(audience).anyMatch(clientId::equals) && (audience.size() == 1 || clientId.equals(claims.path("azp").asText()))
                : clientId.equals(audience.asText());
        if (!audienceOk) {
            throw new OidcException("The ID token is not meant for this client");
        }
        long exp = claims.path("exp").asLong(0);
        if (exp == 0 || now.getEpochSecond() > exp + SKEW_SECONDS) {
            throw new OidcException("The ID token has expired");
        }
        long iat = claims.path("iat").asLong(0);
        if (iat != 0 && iat > now.getEpochSecond() + SKEW_SECONDS) {
            throw new OidcException("The ID token was issued in the future");
        }
        if (expectedNonce == null || !expectedNonce.equals(claims.path("nonce").asText(""))) {
            throw new OidcException("The nonce of the ID token does not match");
        }
        String subject = claims.path("sub").asText("");
        String email = claims.path("email").asText("");
        if (subject.isBlank() || email.isBlank()) {
            throw new OidcException("The ID token names no subject or no e-mail address");
        }
        // Only an address the provider vouches for may be matched to an account; "true" as text is not accepted.
        if (!claims.path("email_verified").isBoolean() || !claims.path("email_verified").asBoolean()) {
            throw new OidcException("The provider does not vouch for the e-mail address");
        }
        return new Identity(subject, email);
    }

    private static java.util.stream.Stream<String> streamOf(JsonNode array) {
        java.util.List<String> values = new java.util.ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values.stream();
    }

    private JsonNode parse(String segment) {
        try {
            return json.readTree(Base64.getUrlDecoder().decode(segment));
        }
        catch (IOException | IllegalArgumentException ex) {
            throw new OidcException("The ID token is malformed", ex);
        }
    }

    private PublicKey keyFor(String kid) {
        JsonNode jwk = keys.get(kid);
        if (jwk == null || keysLoadedAt.plus(CACHE).isBefore(Instant.now())) {
            JsonNode set = get(discovery().jwksUri());
            keys.clear();
            for (JsonNode candidate : set.path("keys")) {
                if ("RSA".equals(candidate.path("kty").asText()) && (candidate.path("use").isMissingNode() || "sig".equals(candidate.path("use").asText()))) {
                    keys.put(candidate.path("kid").asText(""), candidate);
                }
            }
            keysLoadedAt = Instant.now();
            jwk = keys.get(kid);
        }
        if (jwk == null) {
            throw new OidcException("The provider publishes no key " + kid);
        }
        try {
            BigInteger n = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.path("n").asText()));
            BigInteger e = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.path("e").asText()));
            return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e));
        }
        catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new OidcException("The published key is unusable", ex);
        }
    }
}
