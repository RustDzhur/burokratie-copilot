package de.buerokratiecopilot.mail.oauth;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import de.buerokratiecopilot.audit.AuditService;
import de.buerokratiecopilot.audit.domain.AuditEvent;
import de.buerokratiecopilot.mail.inbound.MailAccountService;
import de.buerokratiecopilot.shared.config.BurokratieProperties;
import de.buerokratiecopilot.shared.error.ApiErrorResponse;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Connects a Microsoft 365 or Google Workspace mailbox through the OAuth authorization-code flow (spec §7.2).
 *
 * <p><strong>What this flow can and cannot claim.</strong> The code below performs the standard flow against
 * the providers' documented endpoints: a redirect with {@code state} and PKCE (S256), a code exchange with private
 * client credentials and the verifier, token storage through the platform's existing {@code CredentialCipher},
 * and a first read over IMAP XOAUTH2. It is tested end-to-end against a local stand-in token endpoint, which
 * proves the protocol handling. It has <em>not</em> been run against Microsoft or Google: that needs a
 * registered application, a tenant and a real mailbox, and the project does not pretend otherwise. The
 * adapter catalogue carries that distinction as {@code IMPLEMENTED_NEEDS_APP_REGISTRATION}.</p>
 *
 * <p><strong>The address comes from the provider, not from the browser.</strong> After the exchange the
 * user-info endpoint is asked who granted access; the mailbox is registered under that answer. A caller
 * cannot connect somebody else's address by editing a query parameter.</p>
 *
 * <p><strong>Redirect URI is derived from configuration.</strong> It is
 * {@code <public-base-url>/api/v1/mail/oauth/<provider>/callback}, the same value the operator registers with
 * the provider, so the two can never disagree.</p>
 */
@Service
public class MailOAuthService {

    private static final Logger LOG = LoggerFactory.getLogger(MailOAuthService.class);

    private final MailOAuthProperties properties;
    private final MailOAuthProviderClient client;
    private final MailOAuthState states;
    private final MailAccountService accounts;
    private final AuditService audit;
    private final BurokratieProperties application;

    public MailOAuthService(MailOAuthProperties properties, MailOAuthProviderClient client,
                            MailOAuthState states, MailAccountService accounts, AuditService audit,
                            BurokratieProperties application) {
        this.properties = properties;
        this.client = client;
        this.states = states;
        this.accounts = accounts;
        this.audit = audit;
        this.application = application;
    }

    /** What a started flow hands to the browser. */
    public record Started(URI location, String cookieName, String cookieValue, long cookieMaxAgeSeconds) {
    }

    /** The mailbox that was connected. */
    public record Connected(UUID accountId, String email, MailOAuthProvider provider) {
    }

    /** The views the rest of the application gets; never a token and never a client secret. */
    public Map<String, Object> describe(MailOAuthProvider provider) {
        MailOAuthProperties.Provider settings = client.settings(provider);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("provider", provider.path());
        entry.put("displayName", provider.displayName());
        entry.put("configured", settings.isRegistered());
        entry.put("missingConfiguration", settings.missing());
        entry.put("redirectUri", redirectUri(provider));
        return entry;
    }

    public List<Map<String, Object>> describeAll() {
        List<Map<String, Object>> all = new ArrayList<>();
        for (MailOAuthProvider provider : MailOAuthProvider.values()) {
            all.add(describe(provider));
        }
        return all;
    }

    private String base() {
        return application.getSecurity().getPublicBaseUrl().replaceAll("/+$", "");
    }

    /** The address the operator registers with the provider. */
    public String redirectUri(MailOAuthProvider provider) {
        return base() + "/api/v1/mail/oauth/" + provider.path() + "/callback";
    }

    /**
     * Starts a flow: state, PKCE challenge and the provider's authorization redirect.
     *
     * @throws ApiException when the provider's application is not registered; the details name the missing keys
     */
    public Started start(MailOAuthProvider provider, UUID tenantId, UUID userId) {
        requireRegistered(provider);
        MailOAuthState.State state = states.newState(provider, tenantId, userId);
        MailOAuthProperties.Provider settings = client.settings(provider);
        String authorizeUrl = resolve(settings.getAuthorizeUrl(), settings);
        StringBuilder url = new StringBuilder(authorizeUrl)
                .append(authorizeUrl.contains("?") ? '&' : '?')
                .append("response_type=code")
                .append("&client_id=").append(encode(settings.getClientId()))
                .append("&redirect_uri=").append(encode(redirectUri(provider)))
                .append("&scope=").append(encode(settings.getScope()))
                .append("&state=").append(encode(state.state()))
                .append("&code_challenge=").append(encode(MailOAuthState.challenge(state.verifier())))
                .append("&code_challenge_method=S256");
        for (Map.Entry<String, String> extra : settings.getExtraAuthorizeParameters().entrySet()) {
            if (extra.getValue() != null && !extra.getValue().isBlank()) {
                url.append('&').append(encode(extra.getKey())).append('=').append(encode(extra.getValue()));
            }
        }
        return new Started(URI.create(url.toString()), states.cookieName(provider), states.seal(state),
                states.lifetime().toSeconds());
    }

    /**
     * Finishes the flow: checks the signed state cookie, exchanges the code, reads the address and stores the
     * encrypted tokens.
     *
     * <p>The tenant and the person come from the signed cookie. The callback is a top-level navigation from a
     * third-party site and must not depend on the session cookie being sent — a deployment with
     * {@code SameSite=Strict} would otherwise fail every connection.</p>
     */
    public Connected finish(MailOAuthProvider provider, String code, String state, String cookieValue) {
        requireRegistered(provider);
        MailOAuthState.State stored = states.open(provider, cookieValue);
        if (!MailOAuthState.sameState(state, stored.state())) {
            throw new MailOAuthException("state", "The state does not match the one issued to this browser");
        }
        if (code == null || code.isBlank()) {
            throw new MailOAuthException("code", "The provider returned no authorization code");
        }

        MailOAuthProviderClient.Tokens tokens = client.exchange(provider, code, redirectUri(provider),
                stored.verifier());
        String email = client.emailOf(provider, tokens.accessToken());

        MailOAuthProperties.Provider settings = client.settings(provider);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(MailAccountService.ACCESS_TOKEN, tokens.accessToken());
        if (tokens.refreshToken() != null && !tokens.refreshToken().isBlank()) {
            fields.put(MailAccountService.REFRESH_TOKEN, tokens.refreshToken());
        }
        fields.put(MailAccountService.EXPIRES_AT, tokens.expiresAt().toString());
        fields.put(MailAccountService.TOKEN_TYPE,
                tokens.tokenType() == null || tokens.tokenType().isBlank() ? "Bearer" : tokens.tokenType());
        if (tokens.scope() != null && !tokens.scope().isBlank()) {
            fields.put(MailAccountService.SCOPE, tokens.scope());
        }
        fields.put("connected_at", Instant.now().toString());

        UUID tenantId = UUID.fromString(stored.tenantId());
        UUID userId = UUID.fromString(stored.userId());
        MailAccountService.Account account = accounts.connectOAuth(tenantId, null, provider.databaseValue(),
                email, provider.displayName() + " – " + email, settings.getImapHost(), settings.getImapPort(),
                settings.getSmtpHost(), settings.getSmtpPort(), fields, userId);

        record(tenantId, userId, account.accountId(), provider, email, AuditEvent.Result.SUCCESS,
                AuditEvent.Severity.NOTICE, "Mailbox connected with OAuth");
        LOG.info("Mailbox {} connected with {} for tenant {}", account.accountId(), provider.displayName(),
                tenantId);
        return new Connected(account.accountId(), email, provider);
    }

    /** Records a refused or failed connection without any secret in it. */
    public void recordFailure(MailOAuthProvider provider, String reason) {
        try {
            audit.record(AuditService.AuditEntry.builder(AuditEvent.Category.INTEGRATION, "MAIL_OAUTH_FAILED")
                    .description("Mailbox connection with " + provider.displayName() + " failed: " + reason)
                    .result(AuditEvent.Result.FAILURE).severity(AuditEvent.Severity.NOTICE).build());
        }
        catch (RuntimeException ex) {
            LOG.warn("The failed mail OAuth connection could not be audited", ex);
        }
    }

    private void record(UUID tenantId, UUID userId, UUID accountId, MailOAuthProvider provider, String email,
                        AuditEvent.Result result, AuditEvent.Severity severity, String description) {
        try {
            audit.record(AuditService.AuditEntry.builder(AuditEvent.Category.INTEGRATION, "MAIL_OAUTH_CONNECTED")
                    .tenant(tenantId).actor(AuditEvent.ActorType.USER, userId, null)
                    .entity("MAIL_ACCOUNT", accountId, email)
                    .description(description + " (" + provider.displayName() + ")")
                    .result(result).severity(severity).build());
        }
        catch (RuntimeException ex) {
            LOG.warn("The mail OAuth connection could not be audited", ex);
        }
    }

    /**
     * Refuses to start a flow whose application is not registered, and says exactly what is missing.
     *
     * <p>The answer is a {@code 503} with the missing configuration keys in {@code details}; the browser-facing
     * answer would otherwise be the generic "feature unavailable", which does not tell an operator what to do.</p>
     */
    private void requireRegistered(MailOAuthProvider provider) {
        MailOAuthProperties.Provider settings = client.settings(provider);
        if (settings.isRegistered()) {
            return;
        }
        List<ApiErrorResponse.FieldViolation> missing = new ArrayList<>();
        for (String key : settings.missing()) {
            missing.add(new ApiErrorResponse.FieldViolation(key, "NOT_CONFIGURED",
                    "burokratie.mail.oauth." + (provider == MailOAuthProvider.MICROSOFT365 ? "microsoft." : "google.")
                            + key));
        }
        LOG.warn("{} OAuth is not configured: {}", provider.displayName(), settings.missing());
        throw new ApiException(ErrorCode.FEATURE_DISABLED, provider.displayName()
                + " ist nicht eingerichtet: Die Anwendung wurde noch nicht registriert ("
                + String.join(", ", settings.missing()) + ")", missing);
    }

    /** Replaces the Microsoft tenant placeholder; Google has none. */
    private static String resolve(String url, MailOAuthProperties.Provider settings) {
        if (url == null) {
            return "";
        }
        return url.replace("{tenantId}", URLEncoder.encode(settings.getTenantId(), StandardCharsets.UTF_8));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
