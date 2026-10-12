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
                        '["INBOX"]'::jsonb, 300, '{}'::jsonb, true, true, 0, true, ?)
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
