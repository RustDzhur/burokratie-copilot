package de.buerokratiecopilot.identity.oidc;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import de.buerokratiecopilot.audit.AuditService;
import de.buerokratiecopilot.audit.domain.AuditEvent;
import de.buerokratiecopilot.billing.PlanFeatures;
import de.buerokratiecopilot.identity.auth.AuthService;
import de.buerokratiecopilot.identity.domain.Membership;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.shared.config.BurokratieProperties;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Sign-in through an external identity provider (HD-OIDC): start, and the return of the browser.
 *
 * <p>What is decided here, and why. <strong>No account is created from a token</strong>: a person signs in only if an active
 * account with the verified address already exists, because creating workspaces from whatever a provider sends would turn
 * the provider into the registration form. <strong>Only the Professional plan</strong> may use it (configurable). The
 * state, nonce and PKCE verifier travel in a short-lived, signed cookie, so no server memory is needed and several instances
 * can serve one sign-in. A platform second factor, if the person has one, still applies after the return.</p>
 */
@Service
public class OidcLoginService {

    private static final Logger LOG = LoggerFactory.getLogger(OidcLoginService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long LIFETIME_SECONDS = 600;

    /** The browser is sent to {@code location} and keeps {@code cookieValue} until it returns. */
    public record Start(URI location, String cookieValue) {
    }

    private final OidcProvider provider;
    private final AuthService auth;
    private final UserRepository users;
    private final PlanFeatures plans;
    private final AuditService audit;
    private final BurokratieProperties properties;
    private final byte[] signingKey;

    public OidcLoginService(OidcProvider provider, AuthService auth, UserRepository users, PlanFeatures plans, AuditService audit,
                            BurokratieProperties properties) {
        this.provider = provider;
        this.auth = auth;
        this.users = users;
        this.plans = plans;
        this.audit = audit;
        this.properties = properties;
        byte[] key;
        try {
            key = Base64.getDecoder().decode(properties.getSecurity().getEncryption().getKeyBase64());
        }
        catch (IllegalArgumentException ex) {
            key = new byte[0];
        }
        if (key.length < 16) {
            LOG.warn("No encryption key is configured; the sign-in state is signed with a key of this instance only");
            key = new byte[32];
            RANDOM.nextBytes(key);
        }
        this.signingKey = key;
    }

    public boolean enabled() {
        return provider.enabled();
    }

    private static String random() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        }
        catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 is part of every Java platform", ex);
        }
    }

    private String redirectUri() {
        return properties.getSecurity().getPublicBaseUrl().replaceAll("/+$", "") + "/api/v1/auth/oidc/callback";
    }

    public Start start() {
        if (!enabled()) {
            throw ApiException.notFound("Sign-in provider");
        }
        String state = random();
        String nonce = random();
        String verifier = random();
        String challenge;
        try {
            challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        }
        catch (GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-256 is part of every Java platform", ex);
        }
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                (state + "|" + nonce + "|" + verifier + "|" + (Instant.now().getEpochSecond() + LIFETIME_SECONDS)).getBytes(StandardCharsets.UTF_8));
        return new Start(provider.authorizationUri(redirectUri(), state, nonce, challenge), payload + "." + sign(payload));
    }

    /** Everything that is needed to answer the return of the browser. */
    public AuthService.LoginResult finish(String code, String state, String cookieValue, String ip, String userAgent) {
        if (!enabled()) {
            throw ApiException.notFound("Sign-in provider");
        }
        String[] stored = open(cookieValue);
        if (state == null || !MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8), stored[0].getBytes(StandardCharsets.UTF_8))) {
            throw new OidcProvider.OidcException("The state does not match the one issued to this browser");
        }
        if (code == null || code.isBlank()) {
            throw new OidcProvider.OidcException("No authorisation code");
        }
        OidcProvider.Identity identity = provider.redeem(code, redirectUri(), stored[2], stored[1]);

        Optional<User> user = users.findActiveByEmail(identity.email().strip().toLowerCase(java.util.Locale.ROOT));
        if (user.isEmpty()) {
            denied("No account for the verified address");
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "No account for this identity");
        }
        Optional<Membership> membership = auth.primaryMembership(user.get());
        int needed = properties.getSecurity().getOidc().getMinTier();
        if (membership.isEmpty() || plans.tierOf(membership.get().getTenantId()) < needed) {
            denied("The plan of the workspace does not include this sign-in");
            throw new ApiException(ErrorCode.FORBIDDEN, "This sign-in is not part of the plan of the workspace");
        }
        return auth.loginWithVerifiedIdentity(identity.email(), ip, userAgent);
    }

    private String[] open(String cookieValue) {
        if (cookieValue == null || !cookieValue.contains(".")) {
            throw new OidcProvider.OidcException("No sign-in state cookie");
        }
        int dot = cookieValue.lastIndexOf('.');
        String payload = cookieValue.substring(0, dot);
        if (!MessageDigest.isEqual(sign(payload).getBytes(StandardCharsets.UTF_8), cookieValue.substring(dot + 1).getBytes(StandardCharsets.UTF_8))) {
            throw new OidcProvider.OidcException("The sign-in state cookie is not ours");
        }
        String[] fields = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8).split("\\|");
        if (fields.length != 4 || Long.parseLong(fields[3]) < Instant.now().getEpochSecond()) {
            throw new OidcProvider.OidcException("The sign-in state expired");
        }
        return fields;
    }

    private void denied(String why) {
        try {
            audit.record(AuditService.AuditEntry.builder(AuditEvent.Category.AUTHENTICATION, "OIDC_LOGIN_DENIED")
                    .description(why).result(AuditEvent.Result.DENIED).severity(AuditEvent.Severity.NOTICE).build());
        }
        catch (RuntimeException ex) {
            LOG.warn("The refused OIDC sign-in could not be audited", ex);
        }
    }
}
