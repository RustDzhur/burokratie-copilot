package de.buerokratiecopilot.ingest;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies the signature of an incoming webhook (spec §30, B-013).
 *
 * <p>A webhook endpoint that accepts documents from outside is an open door unless the caller can be
 * shown to hold the shared secret. The signature covers the body and the timestamp, so a captured
 * request cannot be replayed with a different payload or at a much later time.</p>
 *
 * <p>Comparison is constant-time. A comparison that stops at the first differing byte leaks how much of
 * a guessed signature was correct, and a secret can be recovered from enough such answers — this is the
 * one place in the platform where an ordinary {@code equals} would be an actual vulnerability rather
 * than a style question.</p>
 *
 * <p>The scheme is stated rather than implied, because a signature is only meaningful if both sides
 * agree on exactly what was signed: {@code HMAC-SHA256(secret, timestamp + "." + body)}, hex encoded.</p>
 */
public final class WebhookSignature {

    /** How far a timestamp may be from now before a request counts as a replay. */
    public static final long DEFAULT_TOLERANCE_SECONDS = 300;

    private static final String ALGORITHM = "HmacSHA256";

    private WebhookSignature() {
    }

    /** The value a sender has to produce, hex encoded. */
    public static String sign(String secret, long timestampSeconds, byte[] body) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Ohne Geheimnis gibt es keine Signatur");
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            mac.update(String.valueOf(timestampSeconds).getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '.');
            mac.update(body == null ? new byte[0] : body);
            return HexFormat.of().formatHex(mac.doFinal());
        }
        catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException("HMAC-SHA256 ist nicht verfügbar", ex);
        }
    }

    /**
     * Whether a presented signature is valid.
     *
     * @param timestampSeconds the sender's timestamp, as sent
     * @param nowSeconds       the verifier's clock, passed in so the rule is testable without waiting
     */
    public static boolean isValid(String secret, long timestampSeconds, byte[] body, String presented,
                                  long nowSeconds, long toleranceSeconds) {
        if (presented == null || presented.isBlank()) {
            return false;
        }
        if (Math.abs(nowSeconds - timestampSeconds) > toleranceSeconds) {
            // Outside the window the request is not rejected for being wrong but for being late: an old
            // signature is a valid signature of an old request, and replaying it must not work.
            return false;
        }
        String expected = sign(secret, timestampSeconds, body);
        return constantTimeEquals(expected, presented.strip().toLowerCase(java.util.Locale.ROOT));
    }

    /** Convenience with the default tolerance. */
    public static boolean isValid(String secret, long timestampSeconds, byte[] body, String presented,
                                  long nowSeconds) {
        return isValid(secret, timestampSeconds, body, presented, nowSeconds, DEFAULT_TOLERANCE_SECONDS);
    }

    /** Whether a value looks like a hex encoded HMAC-SHA256, before any secret is involved. */
    public static boolean looksLikeSignature(String presented) {
        if (presented == null) {
            return false;
        }
        String trimmed = presented.strip();
        return trimmed.length() == 64 && trimmed.chars().allMatch(character ->
                Character.digit(character, 16) >= 0);
    }

    /**
     * Compares two signatures without leaking where they differ.
     *
     * <p>{@code MessageDigest.isEqual} is constant-time for equal-length inputs; the length check before
     * it is unavoidable and reveals only the length, which is fixed for this scheme.</p>
     */
    static boolean constantTimeEquals(String expected, String presented) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    /** The header names the platform expects, so a sender does not have to guess them. */
    public static java.util.Map<String, String> expectedHeaders() {
        return java.util.Map.of(
                "X-BC-Signature", "Hex encoded HMAC-SHA256 over timestamp + \".\" + body",
                "X-BC-Timestamp", "Unix seconds; must be within " + DEFAULT_TOLERANCE_SECONDS
                        + " seconds of the receiver's clock",
                "X-BC-Idempotency-Key", "Repeat with the same key produces no second document");
    }

    /** A freshly generated webhook secret, for the case where the platform is the one issuing it. */
    public static String newSecret() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** A token that can be shown once and only stored hashed. */
    public static String newApiToken(UUID tenantId) {
        byte[] bytes = new byte[24];
        new java.security.SecureRandom().nextBytes(bytes);
        return "bc_" + (tenantId == null ? "" : tenantId.toString().substring(0, 8)) + "_"
                + HexFormat.of().formatHex(bytes);
    }

    /** The hash under which an API token is stored; the token itself is never kept. */
    public static String hashApiToken(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 ist nicht verfügbar", ex);
        }
    }
}
