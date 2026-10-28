package de.buerokratiecopilot.invoices.outgoing;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.audit.AuditService;
import de.buerokratiecopilot.audit.domain.AuditEvent;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;
import de.buerokratiecopilot.shared.storage.BlobStore;

/**
 * Keeps a generated invoice PDF as a document of the account (spec §6.2, §14.2).
 *
 * <p><strong>Why a document and not just bytes.</strong> The invoice letter carries an attachment, and an
 * attachment is a document id — the product may only attach something the account holds. Rendering the PDF
 * for the letter and throwing it away would mean the customer's copy exists nowhere, cannot be found in the
 * document list, and cannot be attached to anything else. The file is stored with its metadata, its version
 * and its hash, exactly like an uploaded one, so it can be downloaded, searched and sent again.</p>
 *
 * <p><strong>Generated content is not an unchecked upload.</strong> The bytes come from this platform's own
 * renderer, not from a client, so the blob is recorded with {@code SKIPPED} and the note that no upload was
 * scanned. Claiming {@code CLEAN} would say a malware check happened that did not.</p>
 *
 * <p><strong>Identical bytes are stored once.</strong> The schema forbids two live documents with the same
 * content in one account, so a file that is already there is reused and the caller is told which document
 * carries it. Two renders of the same invoice usually differ in the identifiers the PDF writer embeds, and
 * then the second file is stored as its own document — which is correct, because it is the file that was
 * sent that time.</p>
 */
@Service
public class InvoiceDocumentStore {

    private static final Logger log = LoggerFactory.getLogger(InvoiceDocumentStore.class);

    private final BlobStore blobStore;
    private final JdbcTemplate jdbc;
    private final AuditService auditService;

    public InvoiceDocumentStore(BlobStore blobStore, JdbcTemplate jdbc, AuditService auditService) {
        this.blobStore = blobStore;
        this.jdbc = jdbc;
        this.auditService = auditService;
    }

    /** What to store. The invoice identifiers travel with it so the document is traceable to its source. */
    public record Request(UUID tenantId, UUID createdBy, byte[] content, String filename, String mimeType,
                          UUID invoiceId, String documentNumber, String changeNote) {
    }

    /** The stored document, and whether an identical file was already there. */
    public record Stored(UUID documentId, String filename, String mimeType, long sizeBytes, boolean reused) {
    }

    @Transactional
    public Stored store(Request request) {
        if (request.content() == null || request.content().length == 0) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Ein erzeugtes Dokument ohne Inhalt kann nicht gespeichert werden");
        }
        UUID tenantId = request.tenantId();
        String filename = sanitize(request.filename());
        String mimeType = request.mimeType() == null || request.mimeType().isBlank()
                ? "application/octet-stream" : request.mimeType();
        String contentHash = sha256(request.content());

        // Two clicks on the same button in the same second would otherwise race the unique index below.
        // The lock is taken per (tenant, content) and released with the transaction.
        jdbc.query("select pg_advisory_xact_lock(hashtext(?))", rs -> null,
                tenantId + ":" + contentHash);

        List<Stored> existing = jdbc.query("""
                select id, original_filename, mime_type, size_bytes from documents
                where tenant_id = ? and content_hash = ? and deleted_at is null
                  and processing_status <> 'DUPLICATE'
                """, (rs, row) -> new Stored(rs.getObject("id", UUID.class),
                rs.getString("original_filename"), rs.getString("mime_type"),
                rs.getLong("size_bytes"), true), tenantId, contentHash);
        if (!existing.isEmpty()) {
            log.info("Generated file {} already stored as document {}", filename,
                    existing.getFirst().documentId());
            return existing.getFirst();
        }

        BlobStore.StoredBlob stored = blobStore.store(tenantId,
                new ByteArrayInputStream(request.content()), request.content().length, mimeType);
        if (!stored.contentHash().equals(contentHash)) {
            throw new IllegalStateException("The stored file hash does not match the bytes that were written");
        }
        // The object is written before the metadata, as everywhere else. If the metadata cannot be written,
        // the object must not stay behind as something no row points at and no retention rule reaches.
        try {
            UUID blobId = jdbc.queryForObject("""
                    insert into document_blobs (id, tenant_id, storage_key, storage_backend, bucket,
                                                size_bytes, content_hash, content_type, scan_status,
                                                scan_details, quarantined)
                    values (gen_random_uuid(), ?, ?, ?, null, ?, ?, ?, 'SKIPPED',
                            jsonb_build_object('reason', 'Vom System erzeugt; kein Upload geprüft'), false)
                    returning id
                    """, UUID.class, tenantId, stored.storageKey(), stored.backend(), stored.sizeBytes(),
                    contentHash, mimeType);

            UUID documentId = jdbc.queryForObject("""
                    insert into documents (id, tenant_id, original_filename, internal_name, mime_type,
                                           size_bytes, content_hash, current_version, source,
                                           processing_status, review_status, document_number, created_by,
                                           structured_data)
                    values (gen_random_uuid(), ?, ?, ?, ?, ?, ?, 1, 'GENERATED', 'READY', 'NOT_REQUIRED',
                            ?, ?, ?::jsonb)
                    returning id
                    """, UUID.class, tenantId, filename, stored.storageKey(), mimeType, stored.sizeBytes(),
                    contentHash, request.documentNumber(), request.createdBy(),
                    json(Map.of("generated", "INVOICE", "invoiceId",
                            request.invoiceId() == null ? "" : request.invoiceId().toString())));

            jdbc.update("""
                    insert into document_versions (id, document_id, tenant_id, version, blob_id, content_hash,
                                                   size_bytes, mime_type, original_filename, change_note,
                                                   created_by)
                    values (gen_random_uuid(), ?, ?, 1, ?, ?, ?, ?, ?, ?, ?)
                    """, documentId, tenantId, blobId, contentHash, stored.sizeBytes(), mimeType, filename,
                    request.changeNote(), request.createdBy());

            jdbc.update("""
                    insert into document_sources (id, document_id, tenant_id, source_type, raw_metadata)
                    values (gen_random_uuid(), ?, ?, 'GENERATED', ?::jsonb)
                    """, documentId, tenantId, json(Map.of("mimeType", mimeType,
                    "sizeBytes", stored.sizeBytes())));

            auditService.record(AuditService.AuditEntry
                    .builder(AuditEvent.Category.DOCUMENT, "DOCUMENT_GENERATED")
                    .tenant(tenantId)
                    .entity("documents", documentId, filename)
                    .description("Erzeugte Rechnung als Dokument abgelegt")
                    .changes(Map.of("sizeBytes", stored.sizeBytes(), "mimeType", mimeType,
                            "invoiceId", request.invoiceId() == null ? "" : request.invoiceId().toString()))
                    .build());

            log.info("Generated invoice file {} stored as document {} ({} bytes)", filename, documentId,
                    stored.sizeBytes());
            return new Stored(documentId, filename, mimeType, stored.sizeBytes(), false);
        }
        catch (RuntimeException ex) {
            blobStore.delete(stored.storageKey());
            throw ex;
        }
    }

    /**
     * Removes directory components from the name. The name of a generated file is ours, but it is built from
     * the invoice number, and that number comes from a person — a slash in it would travel into a mail part
     * or a download header (spec §21.2).
     */
    static String sanitize(String filename) {
        String cleaned = filename == null ? "" : filename.replace('\\', '/');
        int slash = cleaned.lastIndexOf('/');
        if (slash >= 0) {
            cleaned = cleaned.substring(slash + 1);
        }
        cleaned = cleaned.replaceAll("[\\p{Cntrl}]", "").trim();
        if (cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..")) {
            return "Rechnung.pdf";
        }
        return cleaned.length() > 400 ? cleaned.substring(0, 400) : cleaned;
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is part of every Java platform", ex);
        }
    }

    private static String json(Map<String, Object> values) {
        return de.buerokratiecopilot.workflow.WorkflowService.toJson(values);
    }
}
