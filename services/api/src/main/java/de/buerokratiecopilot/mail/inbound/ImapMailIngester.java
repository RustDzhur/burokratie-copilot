package de.buerokratiecopilot.mail.inbound;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.documents.DocumentService;
import de.buerokratiecopilot.documents.domain.Document;

/**
 * Reads mailboxes over IMAP (spec §7.2, B-014).
 *
 * <p>The acceptance criterion is that an email with an attachment creates a document, and the path is
 * deliberately the same one an upload takes: the attachment becomes a document with the hash of its own
 * bytes, so the duplicate detection the platform already has for uploads applies to mail as well. A
 * second copy of the same invoice arriving by email is refused by the same unique index that refuses a
 * second upload.</p>
 *
 * <p>The mailbox is opened read-only. The platform reads a mailbox; it does not mark messages as seen on
 * behalf of the person whose mailbox it is, because that would change what they see when they open
 * their own mail client.</p>
 *
 * <p>A message that is already stored is skipped rather than updated: the external identifier is unique
 * per account, and re-reading a mailbox is the normal case, not an error.</p>
 */
@Service
public class ImapMailIngester {

    private static final Logger log = LoggerFactory.getLogger(ImapMailIngester.class);

    private final JdbcTemplate jdbc;
    private final MailAccountService accounts;
    private final DocumentService documentService;
    private final org.springframework.transaction.support.TransactionTemplate attachmentTransaction;

    /**
     * Whether the TLS certificate must match the server name.
     *
     * <p>On by default, because a mailbox password crossing a connection to an unverified server is
     * exactly what an attacker on the network wants. It is configurable because some self-hosted mail
     * servers use certificates that do not carry the name the client dials, and the operator — not the
     * platform — has to weigh that. A test that connects to a local server with a generated certificate
     * turns it off explicitly rather than the default being weak for everybody.</p>
     */
    @org.springframework.beans.factory.annotation.Value(
            "${burokratie.mail.imap.check-server-identity:true}")
    private boolean checkServerIdentity = true;

    public ImapMailIngester(JdbcTemplate jdbc, MailAccountService accounts, DocumentService documentService,
                            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.documentService = documentService;
        // One attachment per transaction: a file the security check refuses throws out of the document service,
        // which marks the surrounding transaction rollback-only even though the exception is caught here; the
        // whole message, and every attachment before it, would then be lost on commit.
        this.attachmentTransaction = new org.springframework.transaction.support.TransactionTemplate(
                transactionManager);
        this.attachmentTransaction.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** What a synchronisation produced. */
    public record SyncResult(int seen, int stored, int skipped, int documentsCreated) {

        public Map<String, Object> asDiagnostics() {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("seen", seen);
            details.put("stored", stored);
            details.put("skipped", skipped);
            details.put("documentsCreated", documentsCreated);
            return details;
        }
    }

    /**
     * Reads the INBOX of an account and stores what is new.
     *
     * @param limit how many messages to look at, so a first synchronisation of a large mailbox does not
     *              run for hours; the caller decides how far back to go
     */
    @Transactional
    public SyncResult ingest(UUID tenantId, UUID accountId, int limit) {
        if (!accounts.maySync(tenantId, accountId)) {
            throw ApiException.conflict("Dieses Postfach ist nicht verbunden; es wird nichts abgerufen");
        }
        // One collection per mailbox at a time. The scheduler, the "fetch now" button and a second server can all
        // start together; without this they all see the same new message, all try to store it, and all but one
        // fail on the unique key with a raw database error.
        Boolean mine = jdbc.queryForObject("select pg_try_advisory_xact_lock(hashtextextended(?, 0))",
                Boolean.class, "mail-collect:" + accountId);
        if (!Boolean.TRUE.equals(mine)) {
            log.info("Mailbox {} is already being collected, this run does nothing", accountId);
            return new SyncResult(0, 0, 0, 0);
        }
        String password = accounts.passwordOf(tenantId, accountId);
        String username = username(tenantId, accountId);
        String host = host(tenantId, accountId);
        int port = port(tenantId, accountId);

        int seen = 0;
        int stored = 0;
        int skipped = 0;
        int documents = 0;

        try (Store store = openStore(host, port, username, password)) {
            store.connect(host, port, username, password);
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);

            Message[] messages = inbox.getMessages();
            int from = Math.max(0, messages.length - limit);
            for (int i = from; i < messages.length; i++) {
                seen++;
                Message message = messages[i];
                String externalId = externalIdOf(message);
                if (externalId != null && alreadyStored(tenantId, accountId, externalId)) {
                    // Messages stored before the whole structure of a mail was read have no text: fill it in.
                    backfillBody(tenantId, accountId, externalId, message);
                    skipped++;
                    continue;
                }
                UUID messageId = storeMessage(tenantId, accountId, message, externalId);
                if (messageId == null) {
                    // Stored by somebody else between the check and the insert: not an error.
                    skipped++;
                    continue;
                }
                stored++;
                documents += storeAttachments(tenantId, messageId, message);
            }

            inbox.close(false);
            accounts.recordSync(tenantId, accountId, "SUCCESS", null);
        }
        catch (ApiException ex) {
            throw ex;
        }
        catch (Exception ex) {
            // The mailbox did not answer. The platform records what the server said rather than
            // pretending the mailbox was empty.
            accounts.recordSync(tenantId, accountId, "FAILED", ex.getMessage());
            throw ApiException.conflict("Der Abruf ist fehlgeschlagen: " + ex.getMessage());
        }

        log.info("Mailbox {} read: {} seen, {} stored, {} skipped, {} documents", accountId, seen, stored,
                skipped, documents);
        return new SyncResult(seen, stored, skipped, documents);
    }

    private Store openStore(String host, int port, String username, String password) throws Exception {
        Properties properties = new Properties();
        properties.put("mail.store.protocol", "imaps");
        properties.put("mail.imaps.host", host);
        properties.put("mail.imaps.port", String.valueOf(port));
        properties.put("mail.imaps.ssl.enable", String.valueOf(port != 143));
        properties.put("mail.imaps.ssl.trust", "*");
        properties.put("mail.imaps.ssl.checkserveridentity", String.valueOf(checkServerIdentity));
        properties.put("mail.imaps.timeout", "10000");
        Session session = Session.getInstance(properties);
        return session.getStore("imaps");
    }

    private void backfillBody(UUID tenantId, UUID accountId, String externalId, Message message) {
        Integer missing = jdbc.queryForObject("""
                select count(*) from mail_messages
                where tenant_id = ? and mail_account_id = ? and external_id = ? and body_text is null and body_html is null
                """, Integer.class, tenantId, accountId, externalId);
        if (missing == null || missing == 0) {
            return;
        }
        Body body = bodyOf(message);
        if (body.text() == null && body.html() == null) {
            return;
        }
        jdbc.update("""
                update mail_messages set body_text = ?, body_html = ?
                where tenant_id = ? and mail_account_id = ? and external_id = ? and body_text is null and body_html is null
                """, body.text() != null ? body.text() : de.buerokratiecopilot.mail.MailText.htmlToText(body.html()),
                body.html(), tenantId, accountId, externalId);
    }

    private boolean alreadyStored(UUID tenantId, UUID accountId, String externalId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from mail_messages
                where tenant_id = ? and mail_account_id = ? and external_id = ?
                """, Integer.class, tenantId, accountId, externalId);
        return count != null && count > 0;
    }

    private UUID storeMessage(UUID tenantId, UUID accountId, Message message, String externalId) {
        String subject = safe(() -> message.getSubject());
        String from = firstAddress(message, true);
        String to = firstAddress(message, false);
        boolean hasAttachments = hasAttachments(message);
        Body body = bodyOf(message);

        List<UUID> inserted = jdbc.query("""
                insert into mail_messages (id, tenant_id, mail_account_id, direction, external_id,
                                           subject, from_address, to_addresses, cc_addresses,
                                           bcc_addresses, received_at, body_text, body_html,
                                           has_attachments, attachment_count, category,
                                           is_suspicious, processing_status)
                values (gen_random_uuid(), ?, ?, 'INBOUND', ?, ?, ?, ?::jsonb, '[]'::jsonb, '[]'::jsonb,
                        now(), ?, ?, ?, ?, 'OTHER', false, 'RECEIVED')
                on conflict do nothing
                returning id
                """, (rs, row) -> rs.getObject("id", UUID.class), tenantId, accountId, externalId, subject, from,
                jsonArray(List.of(to)), body.text() != null ? body.text() : (body.html() == null ? null
                        : de.buerokratiecopilot.mail.MailText.htmlToText(body.html())), body.html(), hasAttachments,
                countAttachments(message));
        return inserted.isEmpty() ? null : inserted.getFirst();
    }

    /**
     * Stores the attachments of a message as documents.
     *
     * <p>The hash comes from the bytes themselves, which is what makes the platform's existing duplicate
     * detection work for mail: the same invoice arriving twice is refused by the same unique index that
     * refuses a second upload. When it is refused, the attachment is recorded as a duplicate of the
     * document that already exists rather than as a new one.</p>
     */
    private int storeAttachments(UUID tenantId, UUID messageId, Message message) {
        List<Part> attachments = attachmentsOf(message);
        String sender = firstAddress(message, true);
        int created = 0;
        for (Part part : attachments) {
            try {
                String filename = part.getFileName();
                if (filename == null || filename.isBlank()) {
                    continue;
                }
                byte[] content;
                try (InputStream stream = part.getInputStream()) {
                    content = stream.readAllBytes();
                }
                String hash = sha256(content);
                String mimeType = part.getContentType() == null ? "application/octet-stream"
                        : part.getContentType().split(";")[0].trim()
                                .toLowerCase(java.util.Locale.ROOT);

                // An attachment takes exactly the path of an uploaded file: stored, checked for malware and
                // for a structure that contradicts its type, versioned, announced and audited. The earlier
                // direct insert skipped every one of those steps, marked the file clean without scanning it,
                // and left the document without a version, so it could never be processed.
                UUID existing = existingDocument(tenantId, hash);
                UUID documentId;
                UUID blobRowId;
                if (existing != null) {
                    documentId = existing;
                    blobRowId = blobOf(existing);
                }
                else {
                    // The tenant is set before the transaction opens: the connection takes its tenant when it is
                    // acquired, so the order matters.
                    DocumentService.ReceivedDocument received = RequestContext.runAs(tenantId, () ->
                            attachmentTransaction.execute(status -> documentService.receive(
                                    new DocumentService.UploadRequest(
                                            new ByteArrayMultipartFile(filename, mimeType, content), null, null,
                                            Document.Source.EMAIL, sender))));
                    documentId = received.document().getId();
                    blobRowId = blobOf(documentId);
                    created++;
                }

                jdbc.update("""
                        insert into mail_attachments (id, mail_message_id, tenant_id, filename, mime_type,
                                                      size_bytes, content_hash, blob_id, document_id,
                                                      is_inline)
                        values (gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, messageId, tenantId, filename, mimeType, content.length, hash, blobRowId,
                        documentId, isInline(part));
            }
            catch (Exception ex) {
                // One unreadable attachment must not lose the rest of the message.
                log.warn("Attachment of message {} could not be stored: {}", messageId, ex.getMessage());
            }
        }
        return created;
    }

    private UUID existingDocument(UUID tenantId, String hash) {
        List<UUID> found = jdbc.query("""
                select id from documents
                where tenant_id = ? and content_hash = ? and deleted_at is null
                  and processing_status <> 'DUPLICATE'
                """, (rs, row) -> rs.getObject("id", UUID.class), tenantId, hash);
        return found.isEmpty() ? null : found.getFirst();
    }

    private UUID blobOf(UUID documentId) {
        List<UUID> found = jdbc.query("""
                select blob_id from document_versions where document_id = ? order by version desc limit 1
                """, (rs, row) -> rs.getObject("blob_id", UUID.class), documentId);
        return found.isEmpty() ? null : found.getFirst();
    }

    private String username(UUID tenantId, UUID accountId) {
        return jdbc.queryForObject("""
                select coalesce(imap_username, email_address) from mail_accounts
                where id = ? and tenant_id = ?
                """, String.class, accountId, tenantId);
    }

    private String host(UUID tenantId, UUID accountId) {
        String host = jdbc.queryForObject("select imap_host from mail_accounts where id = ? and tenant_id = ?",
                String.class, accountId, tenantId);
        if (host == null || host.isBlank()) {
            throw ApiException.conflict("Für dieses Postfach ist kein IMAP-Server hinterlegt");
        }
        return host;
    }

    private int port(UUID tenantId, UUID accountId) {
        Integer port = jdbc.queryForObject("select imap_port from mail_accounts where id = ? and tenant_id = ?",
                Integer.class, accountId, tenantId);
        return port == null ? 993 : port;
    }

    /** The message's own identifier; the unique index keys on it so a re-read stores nothing new. */
    private static String externalIdOf(Message message) {
        return safe(() -> {
            String[] ids = message.getHeader("Message-ID");
            return ids == null || ids.length == 0 ? null : ids[0];
        });
    }

    private static String firstAddress(Message message, boolean from) {
        return safe(() -> {
            Address[] addresses = from ? message.getFrom() : message.getAllRecipients();
            return addresses == null || addresses.length == 0 ? null : addresses[0].toString();
        });
    }

    /** The readable parts of a message, wherever they sit in the structure of the mail. */
    record Body(String text, String html) {
    }

    static Body bodyOf(Message message) {
        StringBuilder plain = new StringBuilder();
        StringBuilder html = new StringBuilder();
        try {
            collectBody(message, plain, html);
        }
        catch (Exception ex) {
            // A message that cannot be read to the end keeps what was read so far.
        }
        String text = plain.toString().strip();
        String markup = html.toString().strip();
        if (markup.length() > 500_000) {
            markup = markup.substring(0, 500_000);
        }
        return new Body(text.isEmpty() ? null : text, markup.isEmpty() ? null : markup);
    }

    private static void collectBody(Part part, StringBuilder plain, StringBuilder html) throws Exception {
        String disposition = part.getDisposition();
        if (Part.ATTACHMENT.equalsIgnoreCase(disposition)) {
            return;
        }
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            // In multipart/alternative the same text comes twice (plain and HTML): both are kept, plain is shown.
            for (int i = 0; i < multipart.getCount(); i++) {
                collectBody(multipart.getBodyPart(i), plain, html);
            }
        }
        else if (part.isMimeType("text/plain") && part.getContent() instanceof String text) {
            if (plain.length() > 0) {
                plain.append("\n\n");
            }
            plain.append(text);
        }
        else if (part.isMimeType("text/html") && part.getContent() instanceof String markup) {
            if (html.length() > 0) {
                html.append("\n");
            }
            html.append(markup);
        }
    }

    private static boolean hasAttachments(Message message) {
        return !attachmentsOf(message).isEmpty();
    }

    private static int countAttachments(Message message) {
        return attachmentsOf(message).size();
    }

    private static List<Part> attachmentsOf(Message message) {
        List<Part> found = new ArrayList<>();
        try {
            collect(message, found);
        }
        catch (Exception ex) {
            return found;
        }
        return found;
    }

    private static void collect(Part part, List<Part> found) throws Exception {
        if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())
                || (part.getFileName() != null && !part.isMimeType("text/plain")
                        && !part.isMimeType("text/html"))) {
            found.add(part);
            return;
        }
        if (part.getContent() instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                collect(multipart.getBodyPart(i), found);
            }
        }
    }

    private static boolean isInline(Part part) {
        return Part.INLINE.equalsIgnoreCase(safe(part::getDisposition));
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        }
        catch (Exception ex) {
            throw new IllegalStateException("SHA-256 ist nicht verfügbar", ex);
        }
    }

    private static String jsonArray(List<String> values) {
        return values.stream().filter(java.util.Objects::nonNull)
                .map(value -> "\"" + value.replace("\"", "") + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    /** Runs something that may throw a checked exception and returns null instead of failing. */
    private interface Attempt<T> {
        T get() throws Exception;
    }

    private static <T> T safe(Attempt<T> attempt) {
        try {
            return attempt.get();
        }
        catch (Exception ex) {
            return null;
        }
    }
}
