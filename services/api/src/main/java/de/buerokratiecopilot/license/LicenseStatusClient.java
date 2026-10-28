package de.buerokratiecopilot.license;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Asks the vendor's cabinet whether a licence has been revoked.
 *
 * <p>The call is deliberately tiny and public: it sends the licence's own identifier and the
 * installation id, both of which are already in the signed key. No secret, no token and no
 * customer data leave the installation. The cabinet answers with
 * {@code {"active": true|false, "revokedAt": ..., "reason": ...}}.</p>
 *
 * <p><strong>Failure is not revocation.</strong> A network error, a timeout, a 5xx or a malformed
 * body raises {@link UnavailableException}; the caller keeps the last known state and logs a
 * warning. Only a well-formed {@code active: false} revokes an installation.</p>
 */
@Component
public class LicenseStatusClient {

    private static final Logger log = LoggerFactory.getLogger(LicenseStatusClient.class);

    /** The cabinet answered; this is its verdict. */
    public record Answer(boolean active, Instant revokedAt, String reason) {
    }

    /** The cabinet could not be asked or did not answer in the expected shape. */
    public static class UnavailableException extends RuntimeException {

        public UnavailableException(String message) {
            super(message);
        }

        public UnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final LicenseProperties properties;
    private final ObjectMapper json;
    private final HttpClient http;

    @Autowired
    public LicenseStatusClient(LicenseProperties properties, ObjectMapper json) {
        this(properties, json, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    /** For tests: a client with its own transport. */
    LicenseStatusClient(LicenseProperties properties, ObjectMapper json, HttpClient http) {
        this.properties = properties;
        this.json = json;
        this.http = http;
    }

    /** Whether a remote check is configured at all. */
    public boolean isConfigured() {
        String url = properties.getCheckUrl();
        return url != null && !url.isBlank();
    }

    /**
     * @throws UnavailableException when the cabinet cannot be reached or answers something that is
     *                              not a licence verdict
     */
    public Answer check(String licenseId, String instanceId) {
        if (!isConfigured()) {
            throw new UnavailableException("LICENSE_CHECK_URL is not configured");
        }
        if (licenseId == null || licenseId.isBlank() || instanceId == null || instanceId.isBlank()) {
            throw new UnavailableException("the licence has no id or instance id to check");
        }
        URI uri = URI.create(endpoint() + "?licenseId=" + encode(licenseId) + "&instanceId="
                + encode(instanceId));
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .header("User-Agent", "buerokratie-copilot-runtime")
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new UnavailableException("the licence status console answered HTTP "
                        + response.statusCode());
            }
            JsonNode body = json.readTree(response.body());
            if (body == null || !body.has("active")) {
                throw new UnavailableException("the licence status console answered an unknown body");
            }
            Instant revokedAt = null;
            String rawRevokedAt = body.path("revokedAt").asText(null);
            if (rawRevokedAt != null && !rawRevokedAt.isBlank()) {
                try {
                    revokedAt = Instant.parse(rawRevokedAt);
                }
                catch (RuntimeException ex) {
                    // A malformed timestamp must not turn into a revocation.
                    log.debug("Licence status console returned an unreadable revokedAt value");
                }
            }
            String reason = body.path("reason").asText(null);
            return new Answer(body.path("active").asBoolean(false), revokedAt, reason);
        }
        catch (UnavailableException ex) {
            throw ex;
        }
        catch (IOException ex) {
            throw new UnavailableException("the licence status console could not be reached: "
                    + ex.getClass().getSimpleName(), ex);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new UnavailableException("the licence status check was interrupted", ex);
        }
        catch (RuntimeException ex) {
            throw new UnavailableException("the licence status check failed: "
                    + ex.getClass().getSimpleName(), ex);
        }
    }

    private String endpoint() {
        String url = properties.getCheckUrl().strip();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
