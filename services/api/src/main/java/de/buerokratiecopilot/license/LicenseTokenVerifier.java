package de.buerokratiecopilot.license;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Verifies a compact licence token: {@code base64url(header).base64url(payload).base64url(HMAC)}.
 *
 * <p><strong>Why HMAC and what that means.</strong> The format was chosen because it is small,
 * copy-pasteable and inspectable, and because it needs no extra dependency. It is symmetric: the
 * runtime holds the same secret that signs the licence, so a customer with root access to the
 * installation can mint their own key. The licence is therefore a contractual and operational
 * control (expiry, plan limits, professional support), not a DRM mechanism. That limitation is
 * stated in {@code docs/deployment/RUNTIME.md} rather than hidden behind a "secure" label.</p>
 *
 * <p>The comparison is constant-time. An ordinary {@code equals} on the signature would reveal
 * how much of a guessed signature was correct.</p>
 */
final class LicenseTokenVerifier {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String EXPECTED_ALG = "HS256";
    private static final int TOKEN_VERSION = 1;
    private static final int SIGNATURE_BYTES = 32;

    private LicenseTokenVerifier() {
    }

    enum Reason {
        MALFORMED,
        UNSUPPORTED_ALGORITHM,
        BAD_SIGNATURE,
        UNSUPPORTED_VERSION,
        WRONG_INSTANCE,
        INVALID_PAYLOAD
    }

    static final class InvalidLicenseException extends RuntimeException {

        private final Reason reason;

        InvalidLicenseException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        Reason reason() {
            return reason;
        }
    }

    static LicensePayload verify(String token, String secret, String expectedInstanceId,
                                 ObjectMapper mapper) {
        if (token == null || token.isBlank()) {
            throw new InvalidLicenseException(Reason.MALFORMED, "The licence key is empty");
        }
        if (secret == null || secret.isBlank()) {
            throw new InvalidLicenseException(Reason.BAD_SIGNATURE,
                    "No licence secret is configured, so no key can be verified");
        }
        if (expectedInstanceId == null || expectedInstanceId.isBlank()) {
            throw new InvalidLicenseException(Reason.WRONG_INSTANCE,
                    "The installation has no instance identifier");
        }

        String[] parts = token.strip().split("\\.", -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            throw new InvalidLicenseException(Reason.MALFORMED,
                    "A licence token has three dot-separated parts");
        }

        JsonNode header = readJson(parts[0], mapper);
        if (!EXPECTED_ALG.equals(header.path("alg").asText())) {
            throw new InvalidLicenseException(Reason.UNSUPPORTED_ALGORITHM,
                    "Only HS256 licences are supported");
        }

        byte[] presented = decode(parts[2]);
        byte[] expected = sign(secret, parts[0] + "." + parts[1]);
        if (presented.length != SIGNATURE_BYTES
                || !MessageDigest.isEqual(expected, presented)) {
            throw new InvalidLicenseException(Reason.BAD_SIGNATURE,
                    "The licence signature does not match");
        }

        JsonNode payloadNode = readJson(parts[1], mapper);
        LicensePayload payload;
        try {
            payload = mapper.treeToValue(payloadNode, LicensePayload.class);
        }
        catch (Exception ex) {
            throw new InvalidLicenseException(Reason.INVALID_PAYLOAD,
                    "The licence payload is not readable");
        }
        if (payload == null) {
            throw new InvalidLicenseException(Reason.INVALID_PAYLOAD,
                    "The licence payload is empty");
        }
        if (payload.v() != TOKEN_VERSION) {
            throw new InvalidLicenseException(Reason.UNSUPPORTED_VERSION,
                    "Unsupported licence version: " + payload.v());
        }
        requireText(payload.licenseId(), "licenseId");
        requireText(payload.customer(), "customer");
        requireText(payload.plan(), "plan");
        requireText(payload.instanceId(), "instanceId");
        if (payload.issuedAt() <= 0) {
            throw new InvalidLicenseException(Reason.INVALID_PAYLOAD,
                    "issuedAt must be positive Unix seconds");
        }
        // expiresAt is optional. A key without it is perpetual, which is the default the vendor
        // issues; a key that carries it keeps the expiry/grace/read-only behaviour.
        if (payload.expiresAt() != null) {
            if (payload.expiresAt() <= 0) {
                throw new InvalidLicenseException(Reason.INVALID_PAYLOAD,
                        "expiresAt must be a positive number of Unix seconds when present");
            }
            if (payload.expiresAt() <= payload.issuedAt()) {
                throw new InvalidLicenseException(Reason.INVALID_PAYLOAD,
                        "expiresAt must be later than issuedAt");
            }
        }
        if (!expectedInstanceId.equals(payload.instanceId())) {
            // The key is valid but belongs to another installation. Do not echo either id into
            // the log: the reason is enough for an operator, and the ids are not secrets but they
            // are also not useful to a caller.
            throw new InvalidLicenseException(Reason.WRONG_INSTANCE,
                    "The licence belongs to a different installation");
        }
        int maxAgents = payload.maxAgents();
        long maxRuns = payload.maxRunsPerMonth();
        if (maxAgents < 0 || maxRuns < 0) {
            throw new InvalidLicenseException(Reason.INVALID_PAYLOAD,
                    "Licence limits must not be negative");
        }
        return new LicensePayload(payload.v(), payload.licenseId(), payload.customer(),
                payload.instanceId(), payload.plan(), maxAgents, maxRuns,
                payload.features() == null ? List.of() : List.copyOf(payload.features()),
                payload.issuedAt(), payload.expiresAt());
    }

    /**
     * The state after the cryptographic checks: expiry only moves an installation into
     * grace/read-only, and a key without an expiry never leaves {@code LICENSE_ACTIVE}.
     *
     * <p>Revocation is not part of this calculation: it is a remote fact, applied by
     * {@link LicenseService} on top of the expiry state.</p>
     */
    static LicenseState stateFor(LicensePayload payload, Instant now, int graceDays) {
        Instant expires = payload.expiresAtInstant();
        if (expires == null) {
            return LicenseState.LICENSE_ACTIVE;
        }
        if (!now.isAfter(expires)) {
            return LicenseState.LICENSE_ACTIVE;
        }
        Instant graceEnds = expires.plusSeconds(Math.max(0, graceDays) * 86_400L);
        return now.isAfter(graceEnds) ? LicenseState.LICENSE_READ_ONLY : LicenseState.LICENSE_GRACE;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidLicenseException(Reason.INVALID_PAYLOAD,
                    "The licence field " + field + " is missing");
        }
    }

    private static JsonNode readJson(String part, ObjectMapper mapper) {
        try {
            byte[] decoded = decode(part);
            return mapper.readTree(new String(decoded, StandardCharsets.UTF_8));
        }
        catch (InvalidLicenseException ex) {
            throw ex;
        }
        catch (Exception ex) {
            throw new InvalidLicenseException(Reason.MALFORMED,
                    "The licence token is not valid base64url JSON");
        }
    }

    private static byte[] decode(String value) {
        try {
            return Base64.getUrlDecoder().decode(value);
        }
        catch (IllegalArgumentException ex) {
            throw new InvalidLicenseException(Reason.MALFORMED,
                    "The licence token is not valid base64url");
        }
    }

    private static byte[] sign(String secret, String signingInput) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
        }
        catch (Exception ex) {
            throw new IllegalStateException("HMAC-SHA256 is not available", ex);
        }
    }
}
