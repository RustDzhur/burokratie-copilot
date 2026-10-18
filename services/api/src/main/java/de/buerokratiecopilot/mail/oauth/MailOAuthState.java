package de.buerokratiecopilot.mail.oauth;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import de.buerokratiecopilot.shared.config.BurokratieProperties;

/**
 * The state and PKCE material of one OAuth conversation, carried in a short-lived signed cookie.
 *
 * <p>Two things have to survive the trip through the provider: the {@code state} value and the PKCE
 * verifier. They travel in one cookie that is signed with the platform's encryption key, so no server
 * memory is needed and several instances can serve the same sign-in. The cookie is {@code HttpOnly} (a
 * script must not be able to read the verifier), {@code SameSite=Lax} (it has to come back with the
 * top-level navigation from the provider) and expires after ten minutes.</p>
 *
 * <p>The payload also names the tenant and the person who started the flow. That is not decoration: the
 * callback arrives as a top-level navigation and may reach a deployment whose session cookie is
 * {@code SameSite=Strict}. The signed payload says whose mailbox is being connected, and it is the only
 * source for those identifiers — never a query parameter the browser could choose.</p>
 *
 * <p>The state value is compared in constant time, the signature is checked before anything else is read,
 * and an expired cookie is refused. There is no "resume anyway" path.</p>
 */
@Component
public class MailOAuthState {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long LIFETIME_SECONDS = 600;

    /** What a started flow carries until the provider sends the browser back. */
    public record State(String provider, String tenantId, String userId, String state, String verifier,
                        long expiresAtEpochSeconds) {
    }

    private final byte[] signingKey;

    public MailOAuthState(BurokratieProperties properties) {
        String configured = properties.getSecurity().getEncryption().getKeyBase64();
        byte[] key;
        try {
            key = Base64.getDecoder().decode(configured == null ? "" : configured.strip());
        }
        catch (IllegalArgumentException ex) {
            key = new byte[0];
        }
        if (key.length < 16) {
            // CredentialCipher refuses to start without a key, so this is a programming error rather than a
            // deployment mode: an unsigned state cookie would let a caller choose whose mailbox is connected.
            throw new IllegalStateException("BC_ENCRYPTION_KEY is required for the OAuth state cookie");
        }
        this.signingKey = key;
    }

    /** The cookie name of one provider, so two flows in two tabs do not overwrite each other. */
    public String cookieName(MailOAuthProvider provider) {
        return "bc_mail_oauth_" + provider.path();
    }

    public State newState(MailOAuthProvider provider, java.util.UUID tenantId, java.util.UUID userId) {
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        RANDOM.nextBytes(random);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        return new State(provider.path(), tenantId.toString(), userId.toString(), state, verifier,
                Instant.now().getEpochSecond() + LIFETIME_SECONDS);
    }

    /** The S256 challenge of the verifier, as RFC 7636 defines it. */
    public static String challenge(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        }
        catch (GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-256 is part of every Java platform", ex);
        }
    }

    /** The value of the cookie for a started flow, signed. */
    public String seal(State state) {
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(String.join("|",
                state.provider(), state.tenantId(), state.userId(), state.state(), state.verifier(),
                Long.toString(state.expiresAtEpochSeconds())).getBytes(StandardCharsets.UTF_8));
        return payload + "." + sign(payload);
    }

    /**
     * Reads a state cookie and refuses it for any reason but "this is exactly the flow this browser started".
     *
     * @throws MailOAuthException when the cookie is missing, not ours, expired, or belongs to another provider
     */
    public State open(MailOAuthProvider expectedProvider, String cookieValue) {
        if (cookieValue == null || !cookieValue.contains(".")) {
            throw new MailOAuthException("state", "No OAuth state cookie");
        }
        int dot = cookieValue.lastIndexOf('.');
        String payload = cookieValue.substring(0, dot);
        if (!MessageDigest.isEqual(sign(payload).getBytes(StandardCharsets.UTF_8),
                cookieValue.substring(dot + 1).getBytes(StandardCharsets.UTF_8))) {
            throw new MailOAuthException("state", "The OAuth state cookie is not ours");
        }
        String[] fields;
        try {
            fields = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8).split("\\|", -1);
        }
        catch (IllegalArgumentException ex) {
            throw new MailOAuthException("state", "The OAuth state cookie is not readable", ex);
        }
        if (fields.length != 6) {
            throw new MailOAuthException("state", "The OAuth state cookie is incomplete");
        }
        long expiresAt;
        try {
            expiresAt = Long.parseLong(fields[5]);
        }
        catch (NumberFormatException ex) {
            throw new MailOAuthException("state", "The OAuth state cookie has no expiry", ex);
        }
        if (expiresAt < Instant.now().getEpochSecond()) {
            throw new MailOAuthException("state", "The OAuth state cookie expired");
        }
        if (!expectedProvider.path().equals(fields[0])) {
            throw new MailOAuthException("state", "The OAuth state cookie belongs to another provider");
        }
        return new State(fields[0], fields[1], fields[2], fields[3], fields[4], expiresAt);
    }

    /** Whether the state the provider sent back is the one issued to this browser. Constant-time. */
    public static boolean sameState(String fromProvider, String fromCookie) {
        if (fromProvider == null || fromCookie == null) {
            return false;
        }
        return MessageDigest.isEqual(fromProvider.getBytes(StandardCharsets.UTF_8),
                fromCookie.getBytes(StandardCharsets.UTF_8));
    }

    /** How long a state cookie lives; the controller uses it as the cookie's {@code Max-Age}. */
    public Duration lifetime() {
        return Duration.ofSeconds(LIFETIME_SECONDS);
    }

    /** Encodes one query value the same way everywhere, so a scope with spaces is not mangled. */
    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        }
        catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 is part of every Java platform", ex);
        }
    }
}
