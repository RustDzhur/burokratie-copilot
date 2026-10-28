package de.buerokratiecopilot.mail.inbound;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.integrations.CredentialCipher;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Mailboxes the platform reads from (spec §7.2, B-014).
 *
 * <p>The acceptance criterion is that a mailbox password is not stored in the clear, and that is why
 * this class exists rather than a plain insert: the password goes through the same
 * {@link CredentialCipher} the integration hub uses, bound to the tenant and the account, so a stolen
 * payload cannot be moved into another row and decrypted there.</p>
 *
 * <p>Nothing here claims a mailbox works. An account is {@code DISCONNECTED} until a connection has
 * actually succeeded, and a failure records what the server said. A platform that showed "connected"
 * after storing a password would report an intention rather than a fact.</p>
 */
@Service
public class MailAccountService {

    private static final Logger log = LoggerFactory.getLogger(MailAccountService.class);

    /** The providers the schema allows. */
    private static final List<String> PROVIDERS = List.of("IMAP", "MICROSOFT365", "GMAIL", "SMTP_ONLY",
            "OTHER");

    /**
     * The fields of the encrypted OAuth token bundle. They are stored through the same
     * {@link CredentialCipher} as a mailbox password, bound to the tenant and the mailbox, so an OAuth
     * connection needs no second secret store and a stolen payload cannot be moved into another row.
     */
    public static final String ACCESS_TOKEN = "access_token";
    public static final String REFRESH_TOKEN = "refresh_token";
    public static final String EXPIRES_AT = "expires_at";
    public static final String TOKEN_TYPE = "token_type";
    public static final String SCOPE = "scope";
    public static final String REFRESHED_AT = "refreshed_at";

    private final JdbcTemplate jdbc;
    private final CredentialCipher cipher;

    public MailAccountService(JdbcTemplate jdbc, CredentialCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    /** What a caller provides to register a mailbox. */
    public record Connection(UUID tenantId, UUID workspaceId, String displayName, String emailAddress,
                             String provider, String imapHost, Integer imapPort, String imapUsername,
                             boolean imapUseSsl, String smtpHost, Integer smtpPort, String smtpUsername,
                             String password, UUID createdBy) {
    }

    /** The registered mailbox. */
    public record Account(UUID accountId, String status, String emailAddress) {
    }

    /**
     * Registers a mailbox and stores its password encrypted.
     *
     * <p>The password is encrypted before it reaches the database, and the test that checks this reads
     * the stored column rather than this method's own report: a platform that claimed to encrypt and did
     * not would pass any check that trusted it.</p>
     */
    @Transactional
    public Account register(Connection connection) {
        if (connection.displayName() == null || connection.displayName().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Ein Postfach braucht einen Namen");
        }
        if (connection.emailAddress() == null || connection.emailAddress().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Ein Postfach braucht eine Adresse");
        }
        String provider = connection.provider() == null || connection.provider().isBlank() ? "IMAP"
                : connection.provider().toUpperCase(java.util.Locale.ROOT);
        if (!PROVIDERS.contains(provider)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Unbekannter Anbieter: " + provider);
        }
        if (List.of("MICROSOFT365", "GMAIL").contains(provider)) {
            // They authenticate with OAuth tokens, not a password. Accepting a password here would create a
            // mailbox that the ingest then refuses to read, and nobody would understand why.
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Microsoft 365 und Google Workspace werden "
                    + "über die OAuth-Verbindung eingerichtet, nicht mit einem Passwort");
        }

        UUID accountId = UUID.randomUUID();
        // The password is bound to the account it belongs to; a secret without a row to bind it to would
        // make the associated data meaningless.
        String encrypted = connection.password() == null || connection.password().isBlank() ? null
                : cipher.encrypt(connection.password(), connection.tenantId(), accountId);

        jdbc.update("""
                insert into mail_accounts (id, tenant_id, workspace_id, display_name, email_address,
                                           provider, status, imap_host, imap_port, imap_username,
                                           imap_use_ssl, smtp_host, smtp_port, smtp_username,
                                           smtp_use_starttls, credential_encrypted, sync_folders,
                                           sync_interval, routing_rules, import_attachments,
                                           import_emails_as_documents, consecutive_failures, is_active,
                                           created_by)
                values (?, ?, ?, ?, ?, ?, 'DISCONNECTED', ?, ?, ?, ?, ?, ?, ?, true, ?,
                        '["INBOX"]'::jsonb, 'PT5M', '{}'::jsonb, true, true, 0, true, ?)
                """, accountId, connection.tenantId(), connection.workspaceId(),
                connection.displayName().strip(),
                connection.emailAddress().strip().toLowerCase(java.util.Locale.ROOT), provider,
                connection.imapHost(), connection.imapPort() == null ? 993 : connection.imapPort(),
                connection.imapUsername() == null ? connection.emailAddress() : connection.imapUsername(),
                connection.imapUseSsl(), connection.smtpHost(),
                connection.smtpPort() == null ? 587 : connection.smtpPort(), connection.smtpUsername(),
                encrypted, connection.createdBy());

        log.info("Mailbox {} registered for tenant {} ({})", accountId, connection.tenantId(), provider);
        return new Account(accountId, "DISCONNECTED", connection.emailAddress().strip());
    }

    /** The password of a mailbox, decrypted for use. It is never logged and never returned to a caller. */
    @Transactional(readOnly = true)
    public String passwordOf(UUID tenantId, UUID accountId) {
        List<String> stored = jdbc.query("""
                select credential_encrypted from mail_accounts where id = ? and tenant_id = ?
                """, (rs, row) -> rs.getString("credential_encrypted"), accountId, tenantId);
        if (stored.isEmpty()) {
            throw ApiException.notFound("mail account");
        }
        if (stored.getFirst() == null) {
            throw ApiException.conflict("Für dieses Postfach ist kein Zugang hinterlegt");
        }
        return cipher.decrypt(stored.getFirst(), tenantId, accountId);
    }

    /**
     * Registers a mailbox that is authenticated with OAuth tokens instead of a password.
     *
     * <p>The tokens were obtained from the provider's token endpoint moments ago; they are encrypted
     * before they reach the database, exactly like a password. The mailbox starts {@code DISCONNECTED}:
     * a token exchange proves that the provider issued a token, not that IMAP answered — that is what the
     * first collection is for, and the platform says so rather than showing "connected" for a hope.</p>
     *
     * <p>Reconnecting an address that is already registered updates that mailbox instead of failing on the
     * unique index. A person who connects their mailbox again after the token expired is doing the normal
     * thing, and it must not look like a duplicate error.</p>
     */
    @Transactional
    public Account connectOAuth(UUID tenantId, UUID workspaceId, String provider, String emailAddress,
                                String displayName, String imapHost, int imapPort, String smtpHost,
                                int smtpPort, Map<String, String> tokens, UUID createdBy) {
        String normalizedProvider = provider == null ? "" : provider.toUpperCase(java.util.Locale.ROOT);
        if (!List.of("MICROSOFT365", "GMAIL").contains(normalizedProvider)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "OAuth ist für diesen Anbieter nicht vorgesehen: " + provider);
        }
        if (emailAddress == null || !emailAddress.contains("@")) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Der Anbieter hat keine verwendbare E-Mail-Adresse genannt");
        }
        if (tokens == null || tokens.getOrDefault(ACCESS_TOKEN, "").isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Ein OAuth-Postfach braucht ein Zugangstoken");
        }
        String email = emailAddress.strip().toLowerCase(java.util.Locale.ROOT);
        String name = displayName == null || displayName.isBlank()
                ? provider + " – " + email
                : displayName.strip();

        List<UUID> existing = jdbc.query("""
                select id from mail_accounts
                where tenant_id = ? and email_address = ? and deleted_at is null
                """, (rs, row) -> rs.getObject("id", UUID.class), tenantId, email);
        UUID accountId = existing.isEmpty() ? UUID.randomUUID() : existing.getFirst();
        // The ciphertext is bound to the account it belongs to; therefore the identifier is chosen first.
        String encrypted = cipher.encryptFields(tokens, tenantId, accountId);

        if (existing.isEmpty()) {
            jdbc.update("""
                    insert into mail_accounts (id, tenant_id, workspace_id, display_name, email_address,
                                               provider, status, imap_host, imap_port, imap_username,
                                               imap_use_ssl, smtp_host, smtp_port, smtp_username,
                                               smtp_use_starttls, credential_encrypted, sync_folders,
                                               sync_interval, routing_rules, import_attachments,
                                               import_emails_as_documents, consecutive_failures, is_active,
                                               created_by)
                    values (?, ?, ?, ?, ?, ?, 'DISCONNECTED', ?, ?, ?, true, ?, ?, ?, true, ?,
                            '["INBOX"]'::jsonb, 'PT5M', '{}'::jsonb, true, true, 0, true, ?)
                    """, accountId, tenantId, workspaceId, name, email, normalizedProvider, imapHost, imapPort,
                    email, smtpHost, smtpPort, email, encrypted, createdBy);
        }
        else {
            jdbc.update("""
                    update mail_accounts set display_name = ?, provider = ?, status = 'DISCONNECTED',
                           imap_host = ?, imap_port = ?, imap_username = ?, imap_use_ssl = true,
                           smtp_host = ?, smtp_port = ?, smtp_username = ?,
                           credential_encrypted = ?, consecutive_failures = 0, last_sync_status = null,
                           last_sync_error = null, is_active = true, updated_at = now()
                    where id = ? and tenant_id = ? and deleted_at is null
                    """, name, normalizedProvider, imapHost, imapPort, email, smtpHost, smtpPort, email,
                    encrypted, accountId, tenantId);
        }

        log.info("Mailbox {} connected through OAuth for tenant {} ({})", accountId, tenantId,
                normalizedProvider);
        return new Account(accountId, "DISCONNECTED", email);
    }

    /** The provider of a mailbox, which decides whether it is read with a password or with a token. */
    @Transactional(readOnly = true)
    public String providerOf(UUID tenantId, UUID accountId) {
        List<String> providers = jdbc.query("""
                select provider from mail_accounts where id = ? and tenant_id = ?
                """, (rs, row) -> rs.getString("provider"), accountId, tenantId);
        if (providers.isEmpty()) {
            throw ApiException.notFound("mail account");
        }
        return providers.getFirst();
    }

    /**
     * The decrypted OAuth token bundle of a mailbox. Never logged and never returned by a route.
     *
     * @throws ApiException when the mailbox carries a password rather than tokens, or no credential at all
     */
    @Transactional(readOnly = true)
    public Map<String, String> oauthCredentialsOf(UUID tenantId, UUID accountId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select provider, credential_encrypted from mail_accounts where id = ? and tenant_id = ?
                """, accountId, tenantId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("mail account");
        }
        Map<String, Object> row = rows.getFirst();
        if (!List.of("MICROSOFT365", "GMAIL").contains(String.valueOf(row.get("provider")))) {
            throw ApiException.conflict("Dieses Postfach wird nicht über OAuth angemeldet");
        }
        Object stored = row.get("credential_encrypted");
        if (stored == null) {
            throw ApiException.conflict("Für dieses Postfach ist kein Zugang hinterlegt");
        }
        Map<String, String> fields = cipher.decryptFields(String.valueOf(stored), tenantId, accountId);
        if (fields.isEmpty()) {
            throw ApiException.conflict("Der gespeicherte OAuth-Zugang ist unvollständig");
        }
        return fields;
    }

    /** Replaces the encrypted token bundle after a successful refresh. */
    @Transactional
    public void storeOAuthTokens(UUID tenantId, UUID accountId, Map<String, String> fields) {
        if (fields == null || fields.getOrDefault(ACCESS_TOKEN, "").isBlank()) {
            throw new IllegalArgumentException("Nothing to store without an access token");
        }
        String encrypted = cipher.encryptFields(fields, tenantId, accountId);
        int updated = jdbc.update("""
                update mail_accounts set credential_encrypted = ?, updated_at = now()
                where id = ? and tenant_id = ?
                """, encrypted, accountId, tenantId);
        if (updated == 0) {
            throw ApiException.notFound("mail account");
        }
    }

    /**
     * Records that the stored grant can no longer be used.
     *
     * <p>Runs in its own transaction, like {@link #recordSync}: the caller is about to throw, and the
     * warning that a person has to reconnect must not be rolled back together with the failed attempt.</p>
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void markAuthRequired(UUID tenantId, UUID accountId, String reason) {
        jdbc.update("""
                update mail_accounts set status = 'AUTH_REQUIRED', last_sync_status = 'FAILED',
                       last_sync_error = ?, consecutive_failures = consecutive_failures + 1,
                       updated_at = now()
                where id = ? and tenant_id = ?
                """, reason, accountId, tenantId);
    }

    /**
     * Records the outcome of a connection attempt.
     *
     * <p>Runs in its own transaction. The failure is normally recorded from inside a synchronisation
     * that then throws, and in the same transaction the record would roll back with it — leaving a
     * mailbox that fails every time looking like one that was never tried. The report of a failure has
     * to outlive the failure.</p>
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void recordSync(UUID tenantId, UUID accountId, String outcome, String errorMessage) {
        if ("SUCCESS".equals(outcome)) {
            jdbc.update("""
                    update mail_accounts set status = 'CONNECTED', last_sync_at = now(),
                           last_sync_status = 'SUCCESS', last_sync_error = null,
                           consecutive_failures = 0, updated_at = now()
                    where id = ? and tenant_id = ?
                    """, accountId, tenantId);
        }
        else {
            // The failure count is kept rather than reset, so a mailbox that fails every time becomes
            // visibly broken instead of quietly retrying.
            jdbc.update("""
                    update mail_accounts set status = 'ERROR', last_sync_at = now(),
                           last_sync_status = 'FAILED', last_sync_error = ?,
                           consecutive_failures = consecutive_failures + 1, updated_at = now()
                    where id = ? and tenant_id = ?
                    """, errorMessage, accountId, tenantId);
        }
    }

    /** Whether an account is in a state where a synchronisation may run. */
    @Transactional(readOnly = true)
    public boolean maySync(UUID tenantId, UUID accountId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select status, is_active from mail_accounts where id = ? and tenant_id = ?
                """, accountId, tenantId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("mail account");
        }
        Map<String, Object> row = rows.getFirst();
        return Boolean.TRUE.equals(row.get("is_active"))
                && List.of("CONNECTED", "DISCONNECTED", "ERROR").contains(String.valueOf(row.get("status")));
    }

    /** Every mailbox of the tenant as the interface shows it, with no secret in it. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(UUID tenantId) {
        return jdbc.queryForList("""
                select id, display_name, email_address::text as email_address, provider, status, imap_host, imap_port,
                       last_sync_at, last_sync_status, last_sync_error, consecutive_failures, is_active
                from mail_accounts where tenant_id = ? and deleted_at is null
                order by created_at
                """, tenantId);
    }

    /** The account as the interface shows it, with no secret in it. */
    @Transactional(readOnly = true)
    public Map<String, Object> describe(UUID tenantId, UUID accountId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select display_name, email_address, provider, status, imap_host, imap_port,
                       last_sync_at, last_sync_status, last_sync_error, consecutive_failures, is_active,
                       (credential_encrypted is not null) as has_credential
                from mail_accounts where id = ? and tenant_id = ?
                """, accountId, tenantId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("mail account");
        }
        Map<String, Object> account = new LinkedHashMap<>(rows.getFirst());
        account.put("passwordReadable", false);
        return account;
    }
}
