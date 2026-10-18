package de.buerokratiecopilot.mail.outbound;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;
import de.buerokratiecopilot.shared.storage.BlobStore;

/**
 * Turns the document ids of a draft into the files that actually leave the house (spec §15.3).
 *
 * <p><strong>What was wrong.</strong> A draft has carried {@code attachment_document_ids} and the sending
 * path never looked at the column. The letter went out with its text and without the invoice, and because
 * the draft still showed the attachment, the person who sent it had every reason to believe it had gone
 * with the letter. Two failures in one: the receiver got nothing, and the sender was told nothing.</p>
 *
 * <p><strong>A missing or infected attachment refuses the message.</strong> Dropping it and sending the
 * text would reproduce exactly the defect this class exists to fix, in a quieter way. The user gets a
 * refusal that says which document is the problem and can act on it.</p>
 */
@Component
public class OutboundAttachmentResolver {

    private static final Logger log = LoggerFactory.getLogger(OutboundAttachmentResolver.class);

    /**
     * Upper bound for everything attached to one message. Common relays refuse far less; a message that
     * cannot pass a real server is better refused here, where the user can still act, than bounced later.
     */
    static final long MAX_TOTAL_BYTES = 25L * 1024 * 1024;

    private final JdbcTemplate jdbc;
    private final BlobStore blobStore;

    public OutboundAttachmentResolver(JdbcTemplate jdbc, BlobStore blobStore) {
        this.jdbc = jdbc;
        this.blobStore = blobStore;
    }

    /** One stored file, as far as the message needs to know it. */
    private record StoredFile(String filename, String mimeType, long sizeBytes, String storageKey,
                              String scanStatus) {
    }

    /**
     * Reads the bytes behind the attachment ids.
     *
     * <p>The tenant is part of every lookup: an id from another account does not exist from here, which is
     * the same rule the rest of the product follows. Only the current version of a document is attached —
     * an older version is not what the account holds.</p>
     */
    public List<OutboundSender.Attachment> resolve(UUID tenantId, List<UUID> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return List.of();
        }
        List<OutboundSender.Attachment> attachments = new ArrayList<>(documentIds.size());
        long total = 0;
        for (UUID documentId : documentIds) {
            StoredFile file = storedFile(tenantId, documentId);
            if ("INFECTED".equals(file.scanStatus())) {
                throw ApiException.conflict("Das angehängte Dokument \"" + file.filename()
                        + "\" hat die Sicherheitsprüfung nicht bestanden und wird nicht versendet");
            }
            total += file.sizeBytes();
            if (total > MAX_TOTAL_BYTES) {
                throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE,
                        "Die Anhänge sind zusammen größer als " + (MAX_TOTAL_BYTES / (1024 * 1024))
                                + " MB und können nicht versendet werden");
            }
            attachments.add(new OutboundSender.Attachment(documentId, file.filename(), file.mimeType(),
                    bytes(tenantId, documentId, file)));
        }
        log.debug("Resolved {} attachment(s) for tenant {}", attachments.size(), tenantId);
        return List.copyOf(attachments);
    }

    private StoredFile storedFile(UUID tenantId, UUID documentId) {
        List<StoredFile> rows = jdbc.query("""
                select d.original_filename, coalesce(nullif(b.content_type, ''), d.mime_type) as mime_type,
                       b.size_bytes, b.storage_key, b.scan_status
                from documents d
                join lateral (
                    select v.blob_id
                    from document_versions v
                    where v.document_id = d.id and v.tenant_id = d.tenant_id
                    order by v.version desc
                    limit 1
                ) v on true
                join document_blobs b on b.id = v.blob_id
                where d.id = ? and d.tenant_id = ? and d.deleted_at is null and b.deleted_at is null
                """, (rs, row) -> new StoredFile(rs.getString("original_filename"),
                rs.getString("mime_type"), rs.getLong("size_bytes"), rs.getString("storage_key"),
                rs.getString("scan_status")), documentId, tenantId);
        if (rows.isEmpty()) {
            // Named by id because the file that is missing cannot name itself. The alternative - sending
            // the letter without it - is the defect.
            throw ApiException.conflict("Ein angehängtes Dokument (" + documentId
                    + ") ist nicht mehr vorhanden; die Nachricht wird nicht ohne den Anhang versendet");
        }
        return rows.getFirst();
    }

    private byte[] bytes(UUID tenantId, UUID documentId, StoredFile file) {
        try (InputStream content = blobStore.retrieve(file.storageKey())) {
            byte[] bytes = content.readAllBytes();
            if (bytes.length != file.sizeBytes()) {
                // The stored size is what the download and the scan were based on; a different length
                // means the object does not match its metadata, and that is not something to send.
                throw new IllegalStateException("Stored attachment " + documentId + " has "
                        + bytes.length + " bytes but its metadata says " + file.sizeBytes());
            }
            return bytes;
        }
        catch (IOException ex) {
            throw new de.buerokratiecopilot.shared.storage.BlobStorageException(
                    "Das angehängte Dokument konnte nicht gelesen werden", ex);
        }
    }
}
