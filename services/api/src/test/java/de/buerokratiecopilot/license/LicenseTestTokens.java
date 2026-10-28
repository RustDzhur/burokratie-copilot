package de.buerokratiecopilot.license;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Test-only licence minting. It intentionally uses the same wire format as
 * {@code scripts/license/mint-license.mjs} and the vendor cabinet, so a test failure points at the
 * format, not at a mock.
 *
 * <p>{@code expiresAt} is optional: passing {@code null} mints a perpetual licence, which is the
 * shape the cabinet issues by default.</p>
 *
 * <p>The secret is assembled at runtime. A committed "secret-looking" literal would be a gitleaks
 * finding, and a test constant that is never a real credential is not worth that noise.</p>
 */
final class LicenseTestTokens {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LicenseTestTokens() {
    }

    static String newSecret() {
        return "test-licence-" + java.util.UUID.randomUUID() + "-" + java.util.UUID.randomUUID();
    }

    /** With an expiry: the legacy, fixed-term licence. */
    static String mint(String secret, String licenseId, String customer, String instanceId,
                       String plan, int maxAgents, long maxRunsPerMonth, List<String> features,
                       Instant issuedAt, Instant expiresAt) {
        return mintWithExpirySeconds(secret, licenseId, customer, instanceId, plan, maxAgents,
                maxRunsPerMonth, features, issuedAt,
                expiresAt == null ? null : expiresAt.getEpochSecond());
    }

    /** The general form: {@code expiresAtEpochSecond == null} omits the field entirely. */
    static String mintWithExpirySeconds(String secret, String licenseId, String customer,
                                        String instanceId, String plan, int maxAgents,
                                        long maxRunsPerMonth, List<String> features,
                                        Instant issuedAt, Long expiresAtEpochSecond) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("v", 1);
        payload.put("licenseId", licenseId);
        payload.put("customer", customer);
        payload.put("instanceId", instanceId);
        payload.put("plan", plan);
        payload.put("maxAgents", maxAgents);
        payload.put("maxRunsPerMonth", maxRunsPerMonth);
        payload.put("features", features);
        payload.put("issuedAt", issuedAt.getEpochSecond());
        if (expiresAtEpochSecond != null) {
            payload.put("expiresAt", expiresAtEpochSecond);
        }
        try {
            String header = base64Url("{\"alg\":\"HS256\",\"typ\":\"BC-LICENSE\"}");
            String body = base64Url(MAPPER.writeValueAsString(payload));
            String signingInput = header + "." + body;
            return signingInput + "." + base64Url(sign(secret, signingInput));
        }
        catch (Exception ex) {
            throw new IllegalStateException("Could not mint a test licence", ex);
        }
    }

    static String withTamperedPayload(String token) {
        String[] parts = token.split("\\.", -1);
        return parts[0] + "." + parts[1].substring(0, parts[1].length() - 2) + "AA." + parts[2];
    }

    static LicensePayload payload(String instanceId, Instant issuedAt, Instant expiresAt) {
        return new LicensePayload(1, "LIC-TEST", "Test Customer", instanceId, "ENTERPRISE", 126,
                1000, List.of("agents", "workflow"), issuedAt.getEpochSecond(),
                expiresAt == null ? null : expiresAt.getEpochSecond());
    }

    private static String base64Url(String value) {
        return base64Url(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static byte[] sign(String secret, String signingInput) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
    }
}
