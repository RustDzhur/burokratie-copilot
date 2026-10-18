package de.buerokratiecopilot.mail.oauth;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import de.buerokratiecopilot.mail.inbound.MailAccountService;
import de.buerokratiecopilot.shared.error.ApiException;

/**
 * Keeps the access token of a connected Microsoft 365 or Google Workspace mailbox usable.
 *
 * <p>Two moments at which a token is renewed, and both matter:</p>
 * <ul>
 *   <li><strong>Before it expires.</strong> A collection that starts two seconds before the expiry would
 *       otherwise fail for a reason the platform already knew. The token is renewed when it expires within
 *       two minutes.</li>
 *   <li><strong>When the provider rejects it.</strong> Clock skew and a revoked grant both look like a 401
 *       to the client, and a token that was valid according to its own expiry is exactly the case a
 *       proactive check cannot catch. {@link #refreshAfterUnauthorized} renews once and lets the caller
 *       retry once; a second rejection is a real problem and is reported as one.</li>
 * </ul>
 *
 * <p>When renewal is impossible — the provider granted no refresh token, or it answers
 * {@code invalid_grant} — the mailbox is moved to {@code AUTH_REQUIRED} and the person is asked to connect
 * it again. The platform does not keep retrying with a dead grant: a mailbox that silently retries forever
 * is one nobody notices until a deadline has passed.</p>
 *
 * <p>Tokens are read and written only through {@link MailAccountService}, which encrypts them with the
 * project's {@code CredentialCipher} and binds them to the tenant and the mailbox. This class never touches
 * the stored column directly and never logs a token.</p>
 */
@Service
public class MailOAuthTokenService {

    private static final Logger LOG = LoggerFactory.getLogger(MailOAuthTokenService.class);

    /** Renew this early, so a collection does not start with a token that dies mid-conversation. */
    static final Duration REFRESH_MARGIN = Duration.ofMinutes(2);

    private final MailAccountService accounts;
    private final MailOAuthProviderClient client;

    public MailOAuthTokenService(MailAccountService accounts, MailOAuthProviderClient client) {
        this.accounts = accounts;
        this.client = client;
    }

    /**
     * A usable access token: renewed before expiry when that is possible.
     *
     * @throws ApiException when the mailbox must be connected again
     */
    public String accessToken(UUID tenantId, UUID accountId) {
        Stored stored = load(tenantId, accountId);
        Instant now = Instant.now();
        if (stored.refreshToken() != null && !stored.refreshToken().isBlank()
                && !stored.expiresAt().isAfter(now.plus(REFRESH_MARGIN))) {
            return refresh(tenantId, accountId, stored);
        }
        if (!stored.expiresAt().isAfter(now)) {
            return requiresReauthorisation(tenantId, accountId, stored.provider(),
                    "the access token expired and no refresh token is stored");
        }
        return stored.accessToken();
    }

    /**
     * Renews the token after the provider rejected it, even if its recorded expiry says it is still valid.
     *
     * @throws ApiException when renewal fails; the mailbox then needs a person to connect it again
     */
    public String refreshAfterUnauthorized(UUID tenantId, UUID accountId) {
        Stored stored = load(tenantId, accountId);
        return refresh(tenantId, accountId, stored);
    }

    /** The recorded expiry, for diagnostics; never a token. */
    public Instant expiresAt(UUID tenantId, UUID accountId) {
        return load(tenantId, accountId).expiresAt();
    }

    private Stored load(UUID tenantId, UUID accountId) {
        MailOAuthProvider provider = MailOAuthProvider
                .fromDatabaseValue(accounts.providerOf(tenantId, accountId))
                .orElseThrow(() -> ApiException.conflict(
                        "Dieses Postfach wird nicht über OAuth angemeldet"));
        Map<String, String> fields = accounts.oauthCredentialsOf(tenantId, accountId);
        String accessToken = fields.getOrDefault(MailAccountService.ACCESS_TOKEN, "");
        if (accessToken.isBlank()) {
            throw ApiException.conflict("Für dieses Postfach ist kein Zugangstoken hinterlegt");
        }
        return new Stored(provider, accessToken, fields.getOrDefault(MailAccountService.REFRESH_TOKEN, ""),
                expiresAtOf(fields));
    }

    private static Instant expiresAtOf(Map<String, String> fields) {
        String value = fields.getOrDefault(MailAccountService.EXPIRES_AT, "");
        if (value.isBlank()) {
            return Instant.EPOCH;
        }
        try {
            return Instant.parse(value);
        }
        catch (DateTimeParseException ex) {
            // A value this platform cannot read is treated as expired: renewing once is cheap, using a token
            // of unknown age is not.
            LOG.warn("A mailbox has an unreadable token expiry; treating it as expired");
            return Instant.EPOCH;
        }
    }

    private String refresh(UUID tenantId, UUID accountId, Stored stored) {
        String refreshToken = stored.refreshToken();
        if (refreshToken == null || refreshToken.isBlank()) {
            return requiresReauthorisation(tenantId, accountId, stored.provider(),
                    "the provider granted no refresh token");
        }
        MailOAuthProviderClient.Tokens renewed;
        try {
            renewed = client.refresh(stored.provider(), refreshToken);
        }
        catch (MailOAuthException ex) {
            LOG.warn("Refreshing the {} access token of mailbox {} failed: {}", stored.provider().displayName(),
                    accountId, ex.getMessage());
            return requiresReauthorisation(tenantId, accountId, stored.provider(),
                    "the refresh token was refused");
        }

        Map<String, String> fields = new LinkedHashMap<>(accounts.oauthCredentialsOf(tenantId, accountId));
        fields.put(MailAccountService.ACCESS_TOKEN, renewed.accessToken());
        fields.put(MailAccountService.EXPIRES_AT, renewed.expiresAt().toString());
        fields.put(MailAccountService.TOKEN_TYPE,
                renewed.tokenType() == null || renewed.tokenType().isBlank() ? "Bearer" : renewed.tokenType());
        if (renewed.refreshToken() != null && !renewed.refreshToken().isBlank()) {
            // Providers may rotate the refresh token; when they do not send one, the stored one stays valid.
            fields.put(MailAccountService.REFRESH_TOKEN, renewed.refreshToken());
        }
        if (renewed.scope() != null && !renewed.scope().isBlank()) {
            fields.put(MailAccountService.SCOPE, renewed.scope());
        }
        fields.put(MailAccountService.REFRESHED_AT, Instant.now().toString());
        accounts.storeOAuthTokens(tenantId, accountId, fields);
        LOG.info("Access token of mailbox {} renewed ({})", accountId, stored.provider().displayName());
        return renewed.accessToken();
    }

    private String requiresReauthorisation(UUID tenantId, UUID accountId, MailOAuthProvider provider,
                                           String why) {
        LOG.warn("Mailbox {} must be connected again: {}", accountId, why);
        accounts.markAuthRequired(tenantId, accountId,
                "Die Anmeldung bei " + provider.displayName() + " ist abgelaufen; das Postfach muss erneut "
                        + "verbunden werden.");
        throw ApiException.conflict("Die Anmeldung bei " + provider.displayName()
                + " ist abgelaufen. Bitte das Postfach erneut verbinden.");
    }

    private record Stored(MailOAuthProvider provider, String accessToken, String refreshToken,
                          Instant expiresAt) {
    }
}
