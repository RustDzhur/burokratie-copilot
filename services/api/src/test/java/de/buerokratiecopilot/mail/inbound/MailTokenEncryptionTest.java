package de.buerokratiecopilot.mail.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.integrations.CredentialCipher;

/**
 * Verifies that mailbox credentials are not stored in the clear (spec §7.2, B-014 acceptance).
 *
 * <p>The test reads the stored column rather than asking the service whether it encrypted anything. A
 * platform that claimed to encrypt and did not would pass any check that trusted its own answer, and
 * this is the one claim where trusting the claimant is exactly the mistake.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class MailTokenEncryptionTest {

    @Autowired
    private MailAccountService accounts;

    @Autowired
    private CredentialCipher cipher;

    @Autowired
    private JdbcTemplate jdbc;

    private static final String PASSWORD = "postfach-geheimnis-4711";

    private UUID tenant() {
        return jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'Postfach Test', 'COMPANY') returning id
                """, UUID.class, "mail-" + UUID.randomUUID().toString().substring(0, 12));
    }

    private MailAccountService.Account register(UUID tenantId, String password) {
        return accounts.register(new MailAccountService.Connection(tenantId, null, "Hauptpostfach",
                "postfach@example.de", "IMAP", "imap.example.de", 993, null, true, "smtp.example.de", 587,
                "postfach@example.de", password, null));
    }

    @Test
    @DisplayName("the stored value does not contain the password, in any encoding")
    void storedValueIsNotThePassword() {
        UUID tenantId = tenant();

        MailAccountService.Account account = register(tenantId, PASSWORD);

        String stored = jdbc.queryForObject("""
                select credential_encrypted from mail_accounts where id = ?
                """, String.class, account.accountId());

        assertThat(stored).doesNotContain(PASSWORD);
        assertThat(stored).doesNotContain(java.util.Base64.getEncoder().encodeToString(
                PASSWORD.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(stored).startsWith("v1.");
        assertThat(cipher.looksEncrypted(stored)).isTrue();
    }

    @Test
    @DisplayName("the password survives the round trip and comes back unchanged")
    void passwordRoundTrips() {
        UUID tenantId = tenant();
        MailAccountService.Account account = register(tenantId, PASSWORD);

        assertThat(accounts.passwordOf(tenantId, account.accountId())).isEqualTo(PASSWORD);
    }

    @Test
    @DisplayName("a password encrypted for one mailbox cannot be read as another's")
    void ciphertextIsBoundToItsAccount() {
        UUID tenantId = tenant();
        MailAccountService.Account first = register(tenantId, PASSWORD);

        UUID otherAccount = UUID.randomUUID();
        String encrypted = jdbc.queryForObject("""
                select credential_encrypted from mail_accounts where id = ?
                """, String.class, first.accountId());

        // Moving the payload into another row must not work; that is what the binding is for.
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> cipher.decrypt(encrypted, tenantId, otherAccount)))
                .isInstanceOf(CredentialCipher.CredentialCipherException.class);
    }

    @Test
    @DisplayName("a mailbox without a password is still registered, and says so")
    void passwordlessMailboxIsAllowed() {
        UUID tenantId = tenant();

        MailAccountService.Account account = register(tenantId, null);

        assertThat(jdbc.queryForObject("select credential_encrypted from mail_accounts where id = ?",
                String.class, account.accountId())).isNull();
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> accounts.passwordOf(tenantId, account.accountId())))
                .isInstanceOf(de.buerokratiecopilot.shared.error.ApiException.class);
    }

    @Test
    @DisplayName("the description of a mailbox contains no secret")
    void descriptionContainsNoSecret() {
        UUID tenantId = tenant();
        MailAccountService.Account account = register(tenantId, PASSWORD);

        Map<String, Object> described = accounts.describe(tenantId, account.accountId());

        assertThat(described).containsEntry("has_credential", true)
                .containsEntry("passwordReadable", false);
        assertThat(described.values().stream().map(String::valueOf).toList())
                .allSatisfy(value -> assertThat(value).doesNotContain(PASSWORD));
    }

    @Test
    @DisplayName("a mailbox starts disconnected, because nothing has connected yet")
    void mailboxStartsDisconnected() {
        UUID tenantId = tenant();

        MailAccountService.Account account = register(tenantId, PASSWORD);

        assertThat(account.status())
                .as("storing a password is not the same as a working connection")
                .isEqualTo("DISCONNECTED");
        assertThat(accounts.maySync(tenantId, account.accountId())).isTrue();
    }

    @Test
    @DisplayName("a failed connection marks the mailbox as broken and counts the failures")
    void failureIsRecorded() {
        UUID tenantId = tenant();
        MailAccountService.Account account = register(tenantId, PASSWORD);

        accounts.recordSync(tenantId, account.accountId(), "FAILED", "Authentication failed");
        accounts.recordSync(tenantId, account.accountId(), "FAILED", "Authentication failed");

        Map<String, Object> described = accounts.describe(tenantId, account.accountId());
        assertThat(described).containsEntry("status", "ERROR")
                .containsEntry("consecutive_failures", 2)
                .containsEntry("last_sync_error", "Authentication failed");
    }

    @Test
    @DisplayName("a successful connection clears the failure count")
    void successClearsFailures() {
        UUID tenantId = tenant();
        MailAccountService.Account account = register(tenantId, PASSWORD);
        accounts.recordSync(tenantId, account.accountId(), "FAILED", "Netzwerkfehler");

        accounts.recordSync(tenantId, account.accountId(), "SUCCESS", null);

        Map<String, Object> described = accounts.describe(tenantId, account.accountId());
        assertThat(described).containsEntry("status", "CONNECTED")
                .containsEntry("consecutive_failures", 0);
        assertThat(described.get("last_sync_error")).isNull();
    }

    @Test
    @DisplayName("the same address cannot be registered twice in one tenant")
    void addressIsUniquePerTenant() {
        UUID tenantId = tenant();
        register(tenantId, PASSWORD);

        // The unique index is on (tenant, address) for rows that are not deleted, and the schema is the
        // authority on that rather than this service.
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> register(tenantId, PASSWORD)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    @DisplayName("an unknown provider is refused rather than stored")
    void unknownProviderIsRefused() {
        UUID tenantId = tenant();

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                accounts.register(new MailAccountService.Connection(tenantId, null, "Postfach",
                        "p@example.de", "EXCHANGE_2010", "host", 993, null, true, null, 587, null,
                        PASSWORD, null))))
                .isInstanceOf(de.buerokratiecopilot.shared.error.ApiException.class);
    }

    @Test
    @DisplayName("an address and a name are required")
    void requiredFieldsAreChecked() {
        UUID tenantId = tenant();

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                accounts.register(new MailAccountService.Connection(tenantId, null, "  ",
                        "p@example.de", "IMAP", "host", 993, null, true, null, 587, null, PASSWORD, null))))
                .isInstanceOf(de.buerokratiecopilot.shared.error.ApiException.class);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                accounts.register(new MailAccountService.Connection(tenantId, null, "Postfach",
                        null, "IMAP", "host", 993, null, true, null, 587, null, PASSWORD, null))))
                .isInstanceOf(de.buerokratiecopilot.shared.error.ApiException.class);
    }
}
