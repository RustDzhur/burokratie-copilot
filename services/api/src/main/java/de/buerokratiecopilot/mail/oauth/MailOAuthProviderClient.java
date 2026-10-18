package de.buerokratiecopilot.mail.oauth;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The conversation with Microsoft's and Google's token endpoint: exchange the code, refresh, read the
 * address. Written against the providers' documented HTTP interfaces and the JDK, like the OIDC client
 * in {@code identity.oidc}.
 *
 * <p><strong>No token, code or response body is ever logged.</strong> A failure is logged with the HTTP
 * status and the provider's short {@code error} code; the body can contain the very values this class
 * exists to keep out of log files. The exception messages follow the same rule.</p>
 *
 * <p><strong>PKCE is part of every exchange.</strong> The verifier is sent back and the provider checks
 * it; the whole exchange is only meaningful together with the signed state cookie.</p>
 *
 * <p>Nothing here invents a token lifetime: {@code expires_in} is used as the provider sends it. When a
 * provider omits it (neither Microsoft nor Google does), the token is treated as short-lived and refreshed
 * on the next use or on the first rejection — the platform never assumes a token is still valid because it
 * looks new.</p>
 */
@Component
public class MailOAuthProviderClient {

    private static final Logger LOG = LoggerFactory.getLogger(MailOAuthProviderClient.class);

    /** What the token endpoint answered, with the fields this platform stores. */
    public record Tokens(String accessToken, String refreshToken, Instant expiresAt, String scope,
                         String tokenType) {
    }

    private final MailOAuthProperties properties;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public MailOAuthProviderClient(MailOAuthProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
    }

    /** The registered application of one provider. */
    public MailOAuthProperties.Provider settings(MailOAuthProvider provider) {
        return provider == MailOAuthProvider.MICROSOFT365 ? properties.getMicrosoft() : properties.getGoogle();
    }

    /** Exchanges the authorization code for tokens, proving possession of the PKCE verifier. */
    public Tokens exchange(MailOAuthProvider provider, String code, String redirectUri, String codeVerifier) {
        MailOAuthProperties.Provider settings = settings(provider);
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        form.put("client_id", settings.getClientId());
        form.put("code_verifier", codeVerifier);
        if (!settings.getClientSecret().isBlank()) {
            form.put("client_secret", settings.getClientSecret());
        }
        return post(provider, settings, form, "exchange");
    }

    /** Renews an access token. The provider may or may not rotate the refresh token. */
    public Tokens refresh(MailOAuthProvider provider, String refreshToken) {
        MailOAuthProperties.Provider settings = settings(provider);
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        form.put("client_id", settings.getClientId());
        if (!settings.getClientSecret().isBlank()) {
            form.put("client_secret", settings.getClientSecret());
        }
        return post(provider, settings, form, "refresh");
    }

    /**
     * The mailbox address of the person who granted access.
     *
     * <p>Microsoft Graph {@code /me} answers {@code mail}/{@code userPrincipalName}; Google's user-info
     * endpoint answers {@code email}. Both are asked over TLS with the access token and nothing else. The
     * address is required: a mailbox without one would be stored under a name the person cannot recognise.</p>
     */
    public String emailOf(MailOAuthProvider provider, String accessToken) {
        MailOAuthProperties.Provider settings = settings(provider);
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(settings.getUserInfoUrl()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/json")
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new MailOAuthException("userinfo", "The user-info endpoint rejected the token ("
                        + response.statusCode() + ")");
            }
            if (response.statusCode() != 200) {
                throw new MailOAuthException("userinfo", "The user-info endpoint answered "
                        + response.statusCode());
            }
            JsonNode node = json.readTree(response.body());
            if (provider == MailOAuthProvider.GMAIL && node.has("email_verified")
                    && !node.path("email_verified").asBoolean(false)) {
                throw new MailOAuthException("userinfo", "The provider says the address is not verified");
            }
            for (String field : new String[] { "email", "mail", "userPrincipalName", "preferred_username" }) {
                String value = node.path(field).asText("").strip();
                if (value.contains("@")) {
                    return value;
                }
            }
            throw new MailOAuthException("userinfo", "The provider named no e-mail address");
        }
        catch (IOException ex) {
            throw new MailOAuthException("userinfo", "The user-info request failed", ex);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new MailOAuthException("userinfo", "The user-info request was interrupted", ex);
        }
    }

    private Tokens post(MailOAuthProvider provider, MailOAuthProperties.Provider settings,
                        Map<String, String> form, String reason) {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> entry : form.entrySet()) {
            if (!body.isEmpty()) {
                body.append('&');
            }
            body.append(MailOAuthState.encode(entry.getKey())).append('=')
                    .append(MailOAuthState.encode(entry.getValue()));
        }
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(settings.getTokenUrl()))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                String providerError = errorCode(response.body());
                LOG.warn("{} token request failed: HTTP {}{}", provider.displayName(), response.statusCode(),
                        providerError.isBlank() ? "" : " (" + providerError + ")");
                throw new MailOAuthException(reason, "The token endpoint answered " + response.statusCode()
                        + (providerError.isBlank() ? "" : ": " + providerError));
            }
            JsonNode node = json.readTree(response.body());
            String accessToken = node.path("access_token").asText("");
            if (accessToken.isBlank()) {
                throw new MailOAuthException(reason, "The token endpoint returned no access token");
            }
            String refreshToken = node.path("refresh_token").asText("");
            long seconds = node.path("expires_in").asLong(0);
            if (seconds <= 0) {
                // Neither Microsoft nor Google omits expires_in; an absent value is treated as "short-lived"
                // rather than as "valid forever".
                seconds = 3600;
            }
            return new Tokens(accessToken, refreshToken, Instant.now().plusSeconds(seconds),
                    node.path("scope").asText(""), node.path("token_type").asText("Bearer"));
        }
        catch (IOException ex) {
            throw new MailOAuthException(reason, "The token request failed", ex);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new MailOAuthException(reason, "The token request was interrupted", ex);
        }
    }

    /** The provider's short error code, never its description or body. */
    private String errorCode(String body) {
        try {
            JsonNode node = json.readTree(body);
            String error = node.path("error").asText("");
            // OAuth error codes are short ASCII tokens; anything else is not echoed.
            return error.matches("[A-Za-z0-9_.-]{1,80}") ? error : "";
        }
        catch (IOException | RuntimeException ex) {
            // A non-JSON error page is not worth parsing, and its body is not echoed.
            return "";
        }
    }
}
