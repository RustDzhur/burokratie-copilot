package de.buerokratiecopilot.agents.integration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

/**
 * Small shared helpers for the integration agents. Package-private on purpose: this is
 * implementation detail, not a platform service.
 */
final class IntegrationAgentSupport {

    private IntegrationAgentSupport() {
    }

    /** The string a row carries, or an empty string — never {@code null}. */
    static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** A numeric row value as long, or zero. */
    static long num(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** A label that is never blank, for keys built from optional inputs. */
    static String blankToDash(String value) {
        return blank(value) ? "-" : value.strip();
    }

    static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** Timestamps come back from JDBC in two shapes; both mean the same instant. */
    static Instant toInstant(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        return null;
    }

    /** Whether the last activity is older than the allowed age, or never happened. */
    static boolean stale(Instant now, Object lastSeen, Duration maxAge) {
        Instant seen = toInstant(lastSeen);
        return seen == null || seen.isBefore(now.minus(maxAge));
    }

    /** A stable hash for idempotency keys derived from payloads. */
    static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 ist nicht verfügbar", ex);
        }
    }
}
