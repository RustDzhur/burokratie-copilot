package de.buerokratiecopilot.mail.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.shared.error.ApiException;

/**
 * Verifies reading a mailbox over IMAP (spec §7.2, B-014 acceptance).
 *
 * <p>The criterion is that an email with an attachment creates a document. GreenMail is a real IMAP
 * server running in the test process, so the message is genuinely fetched over the protocol rather than
 * handed to the ingester in memory — a mocked fetch would let the platform "read" a mailbox that does
 * not exist.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
// GreenMail generates a certificate that carries no name the client can verify, so this test relaxes
// hostname checking. The production default stays on, and the relaxation is visible here rather than
// baked into the code for everybody.
@org.springframework.test.context.TestPropertySource(
        properties = "burokratie.mail.imap.check-server-identity=false")
class ImapIngestionIT {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(
            new com.icegreen.greenmail.util.ServerSetup[] { ServerSetupTest.SMTP,
                    ServerSetupTest.IMAPS });

    @Autowired
    private MailAccountService accounts;

    @Autowired
    private ImapMailIngester ingester;

    @Autowired
    private JdbcTemplate jdbc;

    /** GreenMail's IMAP user, created on demand. */
    private static final String USER = "postfach@example.de";
    private static final String PASSWORD = "greenmail-passwort";

    private UUID tenant() {
        return jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'IMAP Test', 'COMPANY') returning id
                """, UUID.class, "imap-" + UUID.randomUUID().toString().substring(0, 12));
    }

    private UUID mailbox(UUID tenantId) {
        greenMail.setUser(USER, USER, PASSWORD);
        return accounts.register(new MailAccountService.Connection(tenantId, null, "Testpostfach", USER,
                "IMAP", "localhost", ServerSetupTest.IMAPS.getPort(), USER, true, null, 587, null,
                PASSWORD, null)).accountId();
    }

    private int documentCount(UUID tenantId) {
        Long count = jdbc.queryForObject("select count(*) from documents where tenant_id = ?", Long.class,
                tenantId);
        return count == null ? 0 : count.intValue();
    }

    @Test
    @DisplayName("an email with an attachment creates a document")
    void emailWithAttachmentCreatesDocument() {
        UUID tenantId = tenant();
        UUID accountId = mailbox(tenantId);

        GreenMailUtil.sendAttachmentEmail(USER, USER, "Rechnung März", "Anbei die Rechnung.",
                "%PDF-1.4\nPDF-Inhalt".getBytes(StandardCharsets.UTF_8), "application/pdf", "rechnung.pdf",
                "Rechnungsdienst", ServerSetupTest.SMTP);

        ImapMailIngester.SyncResult result = ingester.ingest(tenantId, accountId, 10);

        assertThat(result.seen()).isEqualTo(1);
        assertThat(result.stored()).isEqualTo(1);
        assertThat(result.documentsCreated())
                .as("the attachment becomes a document, like any other incoming file")
                .isEqualTo(1);

        var document = jdbc.queryForMap("select original_filename, mime_type, source, content_hash "
                + "from documents where tenant_id = ?", tenantId);
        assertThat(document).containsEntry("original_filename", "rechnung.pdf")
                .containsEntry("mime_type", "application/pdf")
                .containsEntry("source", "EMAIL");
        // The hash comes from the bytes, which is what lets the existing duplicate detection apply.
        assertThat(String.valueOf(document.get("content_hash"))).matches("^[0-9a-f]{64}$");
    }

    @Test
    @DisplayName("the message itself is stored with its sender and subject")
    void messageIsStored() {
        UUID tenantId = tenant();
        UUID accountId = mailbox(tenantId);
        GreenMailUtil.sendTextEmailTest(USER, "absender@example.de", "Anfrage", "Guten Tag");

        ingester.ingest(tenantId, accountId, 10);

        var message = jdbc.queryForMap("select subject, from_address, direction, processing_status "
                + "from mail_messages where tenant_id = ?", tenantId);
        assertThat(message).containsEntry("subject", "Anfrage")
                .containsEntry("direction", "INBOUND")
                .containsEntry("processing_status", "RECEIVED");
        assertThat(String.valueOf(message.get("from_address"))).contains("absender@example.de");
    }

    @Test
    @DisplayName("reading the same mailbox twice stores nothing twice")
    void readingTwiceStoresNothingTwice() {
        UUID tenantId = tenant();
        UUID accountId = mailbox(tenantId);
        GreenMailUtil.sendTextEmailTest(USER, "absender@example.de", "Anfrage", "Guten Tag");

        ingester.ingest(tenantId, accountId, 10);
        ImapMailIngester.SyncResult second = ingester.ingest(tenantId, accountId, 10);

        assertThat(second.stored()).isZero();
        assertThat(second.skipped())
                .as("re-reading a mailbox is the normal case, not an error")
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from mail_messages where tenant_id = ?",
                Integer.class, tenantId)).isEqualTo(1);
    }

    @Test
    @DisplayName("the same invoice arriving twice creates one document, not two")
    void theSameAttachmentIsNotStoredTwice() {
        UUID tenantId = tenant();
        UUID accountId = mailbox(tenantId);
        byte[] content = "%PDF-1.4\nIdentische Rechnung".getBytes(StandardCharsets.UTF_8);

        GreenMailUtil.sendAttachmentEmail(USER, USER, "Rechnung 1", "Erste", content, "application/pdf",
                "rechnung.pdf", "Dienst", ServerSetupTest.SMTP);
        GreenMailUtil.sendAttachmentEmail(USER, USER, "Rechnung 2", "Zweite", content, "application/pdf",
                "rechnung.pdf", "Dienst", ServerSetupTest.SMTP);

        ingester.ingest(tenantId, accountId, 10);

        assertThat(documentCount(tenantId))
                .as("the same content is one document; the schema refuses a second one within a tenant")
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from mail_attachments where tenant_id = ?",
                Integer.class, tenantId)).isEqualTo(2);
    }

    @Test
    @DisplayName("a successful read marks the mailbox as connected")
    void successfulReadMarksConnected() {
        UUID tenantId = tenant();
        UUID accountId = mailbox(tenantId);
        GreenMailUtil.sendTextEmailTest(USER, "absender@example.de", "Anfrage", "Guten Tag");

        ingester.ingest(tenantId, accountId, 10);

        var described = accounts.describe(tenantId, accountId);
        assertThat(described).containsEntry("status", "CONNECTED")
                .containsEntry("last_sync_status", "SUCCESS");
    }

    @Test
    @DisplayName("a mailbox that refuses the password is reported as broken, not as empty")
    void wrongPasswordIsReported() {
        UUID tenantId = tenant();
        greenMail.setUser(USER, USER, "das-richtige-passwort");
        UUID accountId = accounts.register(new MailAccountService.Connection(tenantId, null, "Falsch", USER,
                "IMAP", "localhost", ServerSetupTest.IMAPS.getPort(), USER, true, null, 587, null,
                "das-falsche-passwort", null)).accountId();

        assertThatThrownBy(() -> ingester.ingest(tenantId, accountId, 10))
                .isInstanceOf(ApiException.class);

        // An empty mailbox and an unreachable one are different things, and the user has to be able to
        // tell them apart.
        var described = accounts.describe(tenantId, accountId);
        assertThat(described).containsEntry("status", "ERROR");
        assertThat(described.get("last_sync_error")).isNotNull();
    }

    @Test
    @DisplayName("a disabled mailbox is not read")
    void disabledMailboxIsNotRead() {
        UUID tenantId = tenant();
        UUID accountId = mailbox(tenantId);
        jdbc.update("update mail_accounts set status = 'DISABLED', is_active = false where id = ?",
                accountId);

        assertThatThrownBy(() -> ingester.ingest(tenantId, accountId, 10))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("nicht verbunden");
    }

    @Test
    @DisplayName("a mailbox of another tenant is not read")
    void otherTenantCannotReadTheMailbox() {
        UUID owner = tenant();
        UUID stranger = tenant();
        UUID accountId = mailbox(owner);

        assertThatThrownBy(() -> ingester.ingest(stranger, accountId, 10))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("an empty mailbox is a successful read with nothing in it")
    void emptyMailboxIsSuccess() {
        UUID tenantId = tenant();
        UUID accountId = mailbox(tenantId);

        ImapMailIngester.SyncResult result = ingester.ingest(tenantId, accountId, 10);

        assertThat(result.seen()).isZero();
        assertThat(accounts.describe(tenantId, accountId)).containsEntry("status", "CONNECTED");
    }

    @Test
    @DisplayName("an email without an attachment creates no document")
    void plainEmailCreatesNoDocument() {
        UUID tenantId = tenant();
        UUID accountId = mailbox(tenantId);
        GreenMailUtil.sendTextEmailTest(USER, "absender@example.de", "Nur Text", "Kein Anhang");

        ImapMailIngester.SyncResult result = ingester.ingest(tenantId, accountId, 10);

        assertThat(result.stored()).isEqualTo(1);
        assertThat(result.documentsCreated())
                .as("a message without an attachment is a message, not a document")
                .isZero();
        assertThat(documentCount(tenantId)).isZero();
    }
}
