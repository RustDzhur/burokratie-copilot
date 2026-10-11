package de.buerokratiecopilot.identity.mfa;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Time-based one-time passwords, as RFC 6238 defines them (spec §33, A-014).
 *
 * <p>The algorithm is published and is implemented as published: HMAC-SHA1 over the counter of the
 * current thirty-second step, with the dynamic truncation the RFC specifies. Nothing here is a decision
 * of this platform's — a second factor that deviates from the standard is a second factor whose codes
 * no authenticator application will accept.</p>
 *
 * <p>The one choice that is made here is the drift window, and it is a trade-off rather than a rule: a
 * code is accepted for the step before and the step after, because telephones have clocks that are
 * slightly wrong and a person looking at their screen should not be refused. Widening it further would
 * multiply the number of codes an attacker may try, which is what the window exists to bound.</p>
 */
public final class Totp {

    /** Thirty seconds, as the RFC specifies. */
    public static final int STEP_SECONDS = 30;

    /** Six digits, which is what authenticator applications show. */
    public static final int DIGITS = 6;

    /** How many steps either side of now are accepted: one, for clocks that are slightly wrong. */
    public static final int DRIFT_STEPS = 1;

    private static final String ALGORITHM = "HmacSHA1";

    /** The characters RFC 4648 defines for base32, which is what the provisioning URI carries. */
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Totp() {
    }

    /** A new secret: twenty bytes, the length the RFC recommends for HMAC-SHA1. */
    public static byte[] newSecret() {
        byte[] secret = new byte[20];
        new SecureRandom().nextBytes(secret);
        return secret;
    }

    /**
     * The code for a moment.
     *
     * @param counterStep the counter value; {@link #stepAt(long)} turns a time into one
     */
    public static String code(byte[] secret, long counterStep) {
        try {
            byte[] counter = ByteBuffer.allocate(8).putLong(counterStep).array();
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            byte[] hash = mac.doFinal(counter);

            // The dynamic truncation of RFC 4226: the low nibble of the last byte chooses the offset, and
            // the four bytes from there are read as a 31-bit integer.
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            int code = binary % 1_000_000;
            return String.format("%0" + DIGITS + "d", code);
        }
        catch (Exception ex) {
            throw new IllegalStateException("HMAC-SHA1 ist nicht verfügbar", ex);
        }
    }

    /** The counter step for a moment, in seconds since the epoch. */
    public static long stepAt(long epochSeconds) {
        return epochSeconds / STEP_SECONDS;
    }

    /**
     * Whether a code is valid now.
     *
     * @param epochSeconds the verifier's clock, passed in so the rule is testable without waiting
     */
    public static boolean isValid(byte[] secret, String presented, long epochSeconds) {
        if (secret == null || presented == null) {
            return false;
        }
        String normalized = presented.replaceAll("\\s", "");
        if (!normalized.matches("\\d{" + DIGITS + "}")) {
            return false;
        }
        long step = stepAt(epochSeconds);
        for (int drift = -DRIFT_STEPS; drift <= DRIFT_STEPS; drift++) {
            // Compared digit by digit without an early exit would be over-engineering here: a TOTP code is
            // short-lived and guessed online, and the comparison is not the weak point.
            if (code(secret, step + drift).equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The provisioning URI an authenticator application scans.
     *
     * <p>The format is the one the applications agreed on; a platform that invents its own produces a QR
     * code nobody can read.</p>
     */
    public static String provisioningUri(String issuer, String account, byte[] secret) {
        String label = urlEncode(issuer) + ":" + urlEncode(account);
        return "otpauth://totp/" + label
                + "?secret=" + base32(secret)
                + "&issuer=" + urlEncode(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    /** Base32 without padding, as the provisioning URI expects. */
    public static String base32(byte[] data) {
        StringBuilder encoded = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        for (byte value : data) {
            buffer = (buffer << 8) | (value & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                encoded.append(BASE32.charAt((buffer >> (bitsLeft - 5)) & 0x1F));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            encoded.append(BASE32.charAt((buffer << (5 - bitsLeft)) & 0x1F));
        }
        return encoded.toString();
    }

    /** A recovery code: readable, and long enough that guessing it is not worth trying. */
    public static String newRecoveryCode() {
        byte[] bytes = new byte[10];
        new SecureRandom().nextBytes(bytes);
        String raw = base32(bytes).toLowerCase(Locale.ROOT);
        // Grouped so a person can read it off paper without losing their place.
        return raw.substring(0, 4) + "-" + raw.substring(4, 8) + "-" + raw.substring(8, 12) + "-"
                + raw.substring(12, 16);
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }
}
