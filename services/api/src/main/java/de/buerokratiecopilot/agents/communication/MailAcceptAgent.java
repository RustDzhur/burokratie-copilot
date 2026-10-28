package de.buerokratiecopilot.agents.communication;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.documents.DocumentService;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.storage.BlobStore;

/**
 * {@code mail-accept} — E-Mail-Annahme.
 *
 * <p>Takes the attachments of an incoming mail into the document flow: every attachment that has
 * no document yet is read from the object store and registered through
 * {@link DocumentService#receive} — the same door every upload goes through, so storage, hashing,
 * security scan, versioning, outbox and audit all happen. The resulting documents are linked to
 * the case the caller names (origin {@code IMPORT}), and the mail is marked {@code PROCESSED}.
 *
 * <p>Idempotent: an attachment already carrying a document is skipped, the case link uses
 * {@code on conflict do nothing}, and the processed flag is a plain update — a re-run reports the
 * already accepted documents instead of duplicating them. Attachments without stored bytes are
 * reported honestly, not invented.</p>
 */
@Component
public class MailAcceptAgent implements Agent {

    private final JdbcTemplate jdbc;
    private final DocumentService documentService;
    private final BlobStore blobStore;

    public MailAcceptAgent(JdbcTemplate jdbc, DocumentService documentService, BlobStore blobStore) {
        this.jdbc = jdbc;
        this.documentService = documentService;
        this.blobStore = blobStore;
    }

    @Override
    public String id() {
        return "mail-accept";
    }

    @Override
    public AgentResult run(AgentContext context) {
        UUID mailId;
        try {
            mailId = UUID.fromString(
                    context.input("mailId") == null ? "" : context.input("mailId").strip());
        }
        catch (IllegalArgumentException ex) {
            return AgentResult.failed("mailId fehlt oder ist keine UUID.");
        }

        List<Map<String, Object>> mailRows = jdbc.queryForList("""
                select id, direction, from_name, from_address from mail_messages
                where id = ? and tenant_id = ? and deleted_at is null
                """, mailId, context.tenantId());
        if (mailRows.isEmpty()) {
            return AgentResult.failed("E-Mail nicht gefunden.");
        }
        Map<String, Object> mail = mailRows.getFirst();
        if (!"INBOUND".equals(String.valueOf(mail.get("direction")))) {
            return AgentResult.skipped("Die Nachricht ist keine eingegangene E-Mail.");
        }
        String sender = mail.get("from_name") == null ? null : String.valueOf(mail.get("from_name"));
        if (sender == null || sender.isBlank()) {
            sender = mail.get("from_address") == null ? null
                    : String.valueOf(mail.get("from_address"));
        }

        List<Map<String, Object>> attachments = jdbc.queryForList("""
                select id, filename, mime_type, blob_id, document_id from mail_attachments
                where mail_message_id = ? and is_inline = false
                order by created_at
                """, mailId);
        if (attachments.isEmpty()) {
            return AgentResult.skipped("Die E-Mail hat keine Anhänge.");
        }

        UUID caseId = parseUuid(context.input("caseId"));
        List<AgentResult.Produced> produced = new ArrayList<>();
        List<UUID> documents = new ArrayList<>();
        int accepted = 0;
        int alreadyLinked = 0;
        int withoutBytes = 0;
        int failed = 0;

        for (Map<String, Object> attachment : attachments) {
            Object documentIdRaw = attachment.get("document_id");
            if (documentIdRaw != null) {
                alreadyLinked++;
                UUID documentId = toUuid(documentIdRaw);
                if (documentId != null) {
                    documents.add(documentId);
                }
                continue;
            }
            Object blobIdRaw = attachment.get("blob_id");
            if (blobIdRaw == null) {
                withoutBytes++;
                continue;
            }
            try {
                UUID documentId = acceptAttachment(context, mailId, attachment, sender);
                if (documentId == null) {
                    withoutBytes++;
                    continue;
                }
                documents.add(documentId);
                produced.add(new AgentResult.Produced("document", documentId.toString(),
                        "Anhang als Dokument übernommen: " + attachment.get("filename")));
                accepted++;
            }
            catch (RuntimeException ex) {
                failed++;
                produced.add(new AgentResult.Produced("failure",
                        String.valueOf(attachment.get("filename")),
                        "Anhang konnte nicht übernommen werden: " + ex.getMessage()));
            }
        }

        int caseLinks = 0;
        if (caseId != null && !documents.isEmpty()) {
            for (UUID documentId : documents) {
                caseLinks += jdbc.update("""
                        insert into case_documents (id, case_id, document_id, tenant_id, is_primary,
                                                    link_origin, confidence, linked_by)
                        values (gen_random_uuid(), ?, ?, ?, false, 'IMPORT', 0.5, ?)
                        on conflict (case_id, document_id) do nothing
                        """, caseId, documentId, context.tenantId(), context.actorUserId());
            }
            if (caseLinks > 0) {
                produced.add(new AgentResult.Produced("case_link", caseId.toString(),
                        caseLinks + " Dokument(e) mit dem Vorgang verknüpft"));
            }
        }

        if (accepted == 0 && caseLinks == 0 && alreadyLinked == 0) {
            return AgentResult.skipped(
                    "Kein Anhang konnte übernommen werden (%d ohne gespeicherte Bytes, %d Fehler)."
                            .formatted(withoutBytes, failed));
        }

        jdbc.update("""
                update mail_messages
                set processing_status = 'PROCESSED', processed_at = now(),
                    case_id = coalesce(?, case_id)
                where id = ?
                """, caseId, mailId);

        Map<String, String> meta = Map.of(
                "accepted", String.valueOf(accepted),
                "alreadyLinked", String.valueOf(alreadyLinked),
                "withoutBytes", String.valueOf(withoutBytes),
                "failed", String.valueOf(failed),
                "caseLinks", String.valueOf(caseLinks));
        return AgentResult.done(
                "E-Mail-Annahme: %d Anhang/Anhänge übernommen, %d bereits im Umlauf, %d mit dem "
                        + "Vorgang verknüpft.".formatted(accepted, alreadyLinked, caseLinks),
                produced, meta);
    }

    /**
     * Reads the stored bytes of one attachment and registers them as a document through the
     * standard upload path, then links the attachment row to the new document.
     */
    private UUID acceptAttachment(AgentContext context, UUID mailId, Map<String, Object> attachment,
                                  String sender) {
        UUID blobId = toUuid(attachment.get("blob_id"));
        if (blobId == null) {
            return null;
        }
        String storageKey;
        try {
            storageKey = jdbc.queryForObject(
                    "select storage_key from document_blobs where id = ?", String.class, blobId);
        }
        catch (org.springframework.dao.IncorrectResultSizeDataAccessException ex) {
            return null;
        }
        if (storageKey == null) {
            return null;
        }
        byte[] content;
        try (InputStream in = blobStore.retrieve(storageKey)) {
            content = in.readAllBytes();
        }
        catch (IOException ex) {
            throw new IllegalStateException("Gespeicherte Anhang-Bytes nicht lesbar: "
                    + ex.getMessage(), ex);
        }

        String filename = attachment.get("filename") == null ? "anhang"
                : String.valueOf(attachment.get("filename"));
        String mimeType = attachment.get("mime_type") == null ? "application/octet-stream"
                : String.valueOf(attachment.get("mime_type"));

        DocumentService.ReceivedDocument received = RequestContext.runAs(context.tenantId(), () ->
                documentService.receive(new DocumentService.UploadRequest(
                        new BytesMultipartFile(filename, mimeType, content), null, null,
                        Document.Source.EMAIL, sender)));
        UUID documentId = received.document().getId();
        jdbc.update("update mail_attachments set document_id = ? where id = ?",
                documentId, attachment.get("id"));
        return documentId;
    }

    private static UUID toUuid(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof UUID uuid) {
            return uuid;
        }
        try {
            return UUID.fromString(String.valueOf(value));
        }
        catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static UUID parseUuid(String value) {
        return toUuid(value);
    }

    /** An attachment presented as an uploaded file, so it enters through the standard door. */
    private static final class BytesMultipartFile implements MultipartFile {

        private final String filename;
        private final String contentType;
        private final byte[] content;

        private BytesMultipartFile(String filename, String contentType, byte[] content) {
            this.filename = filename;
            this.contentType = contentType;
            this.content = content;
        }

        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return filename;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public boolean isEmpty() {
            return content.length == 0;
        }

        @Override
        public long getSize() {
            return content.length;
        }

        @Override
        public byte[] getBytes() {
            return content;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(content);
        }

        @Override
        public void transferTo(File dest) throws IOException {
            java.nio.file.Files.write(dest.toPath(), content);
        }
    }
}
