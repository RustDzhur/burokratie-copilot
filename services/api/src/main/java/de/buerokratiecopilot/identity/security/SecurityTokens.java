package de.buerokratiecopilot.identity.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Password hashing and token generation.
 *
 * <p>Passwords are hashed with Argon2id (the algorithm recommended by OWASP and implemented by
 * Spring Security / Bouncy Castle) — never with hand-written cryptography (spec §21.3).</p>
 *
 * <p>Tokens (sessions, e-mail verification, invitations) are random 256-bit values; only their
 * SHA-256 hash is stored, so the database never contains anything that can be replayed.</p>
 */
public final class SecurityTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private SecurityTokens() {
    }

    /** Argon2id encoder with parameters suitable for interactive logins. */
    public static PasswordEncoder passwordEncoder() {
        // saltLength=16, hashLength=32, parallelism=1, memory=19456 KiB (19 MiB), iterations=2
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    /** 256 bit of randomness, URL safe — used as a session token or one-time token. */
    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return URL_ENCODER.encodeToString(bytes);
    }

    /** Short human readable code (e.g. a recovery code); grouped for easier transcription. */
    public static String newHumanCode(int groups, int groupLength) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < groups; i++) {
            if (i > 0) {
                builder.append('-');
            }
            for (int j = 0; j < groupLength; j++) {
                builder.append((char) ('A' + RANDOM.nextInt(26)));
            }
        }
        return builder.toString();
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required but unavailable", ex);
        }
    }
}
