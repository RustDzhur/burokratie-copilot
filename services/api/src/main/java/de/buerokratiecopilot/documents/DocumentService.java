package de.buerokratiecopilot.documents;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import de.buerokratiecopilot.audit.AuditService;
import de.buerokratiecopilot.audit.domain.AuditEvent;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.documents.domain.DocumentBlob;
import de.buerokratiecopilot.documents.domain.DocumentSource;
import de.buerokratiecopilot.documents.domain.DocumentStatusMachine;
import de.buerokratiecopilot.documents.domain.DocumentProcessingStep;
import de.buerokratiecopilot.documents.domain.DocumentVersion;
import de.buerokratiecopilot.documents.pipeline.DocumentProcessingPipeline;
import de.buerokratiecopilot.documents.security.FileScanResult;
import de.buerokratiecopilot.documents.security.FileSecurityScanner;
import de.buerokratiecopilot.documents.security.MalwareScanner;
import de.buerokratiecopilot.shared.config.BurokratieProperties;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;
import de.buerokratiecopilot.shared.outbox.DomainEvent;
import de.buerokratiecopilot.shared.outbox.OutboxWriter;
import de.buerokratiecopilot.shared.storage.BlobStore;

/**
 * Receiving and reading documents (spec §7.1, §6.3).
 *
 * <p>What this service guarantees today:</p>
 * <ul>
 *   <li>the bytes are written to the object store <em>before</em> the metadata is committed, and the
 *       SHA-256 is computed while streaming,</li>
 *   <li>the same content uploaded twice by the same tenant is recognised and recorded as a duplicate
 *       instead of being presented as a new document (spec §7.1, §9.1),</li>
 *   <li>the tenant of a document always comes from the authenticated session, never from the
 *       request,</li>
 *   <li>the processing status can only change along the pipeline of {@link DocumentStatusMachine},</li>
 *   <li>receipt is announced through the outbox in the same transaction as the insert, so a
 *       background worker can pick the document up without polling the table,</li>
 *   <li>access and receipt are audited (spec §21.4).</li>
 * </ul>
 *
 * <p>Deliberately <strong>not</strong> done here: content-based MIME validation, malware scanning and
 * archive bomb protection are task B-003, and text extraction, OCR and classification are B-005…B-009.
 * Until then a stored blob is marked {@code SKIPPED} (no scanner is configured) and the document stays
 * in {@code RECEIVED}; nothing claims that the file was checked.</p>
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final DocumentRepository documents;
    private final DocumentBlobRepository blobs;
    private final DocumentVersionRepository versions;
    private final DocumentSourceRepository sources;
    private final BlobStore blobStore;
    private final OutboxWriter outbox;
    private final AuditService auditService;
    private final BurokratieProperties properties;
    private final FileSecurityScanner securityScanner;
    private final MalwareScanner malwareScanner;
    private final DocumentProcessingPipeline pipeline;

    public DocumentService(DocumentRepository documents, DocumentBlobRepository blobs,
                           DocumentVersionRepository versions, DocumentSourceRepository sources,
                           BlobStore blobStore, OutboxWriter outbox, AuditService auditService,
                           BurokratieProperties properties, FileSecurityScanner securityScanner,
                           MalwareScanner malwareScanner, DocumentProcessingPipeline pipeline) {
        this.documents = documents;
        this.blobs = blobs;
        this.versions = versions;
        this.sources = sources;
        this.blobStore = blobStore;
        this.outbox = outbox;
        this.auditService = auditService;
        this.properties = properties;
        this.securityScanner = securityScanner;
        this.malwareScanner = malwareScanner;
        this.pipeline = pipeline;
    }

    /** Result of a received document: either a new document or a recognised duplicate. */
    public record ReceivedDocument(Document document, boolean duplicate, UUID duplicateOf) {
    }

    public record UploadRequest(MultipartFile file, UUID workspaceId, UUID documentTypeId,
                                Document.Source source, String sender) {
    }

    @Transactional
    public ReceivedDocument receive(UploadRequest request) {
        UUID tenantId = RequestContext.tenantId()
                .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, "No tenant in context"));

        MultipartFile file = request.file();
        if (file == null || file.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "An uploaded file is required");
        }
        long maxBytes = properties.getUpload().getMaxFileSizeBytes();
        if (file.getSize() > maxBytes) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE,
                    "The file exceeds the configured limit of %d bytes".formatted(maxBytes));
        }

        String originalFilename = sanitizeFilename(file.getOriginalFilename());
        String declaredType = normalizeContentType(file.getContentType(), originalFilename);

        BlobStore.StoredBlob stored = storeBlob(tenantId, file, declaredType);

        // The security checks run on the stored bytes before anything else may touch them. A file
        // whose content contradicts its type, or that carries malware, is removed again so no
        // unusable object stays behind.
        FileScanResult scan = scanStoredContent(tenantId, file, declaredType, stored);
        MalwareScanner.Scan malware = scanForMalware(file, declaredType, stored);
        if (malware.verdict() == MalwareScanner.Verdict.INFECTED) {
            blobStore.delete(stored.storageKey());
            auditService.record(AuditService.AuditEntry
                    .builder(AuditEvent.Category.DOCUMENT, "DOCUMENT_MALWARE_DETECTED")
                    .tenant(tenantId)
                    .entity("documents", null, originalFilename)
                    .description("Malware detected by " + malware.engine() + ": " + malware.signature())
                    .severity(AuditEvent.Severity.CRITICAL)
                    .result(AuditEvent.Result.FAILURE)
                    .build());
            throw new ApiException(ErrorCode.FILE_REJECTED,
                    "The file was rejected because malware was detected in it");
        }
        if (scan.isRejected()) {
            blobStore.delete(stored.storageKey());
            auditService.record(AuditService.AuditEntry
                    .builder(AuditEvent.Category.DOCUMENT, "DOCUMENT_REJECTED")
                    .tenant(tenantId)
                    .entity("documents", null, originalFilename)
                    .description("Rejected by the file security check: " + scan.reason())
                    .severity(AuditEvent.Severity.WARNING)
                    .result(AuditEvent.Result.FAILURE)
                    .build());
            throw new ApiException(ErrorCode.FILE_REJECTED, scan.reason());
        }

        Optional<Document> existing = documents
                .findFirstByTenantIdAndContentHashAndDeletedAtIsNull(tenantId, stored.contentHash());

        Document document = new Document(UUID.randomUUID(), tenantId, originalFilename,
                stored.storageKey(), declaredType, stored.sizeBytes(), stored.contentHash(),
                request.source() == null ? Document.Source.UPLOAD : request.source());
        document.setWorkspaceId(request.workspaceId());
        document.setDocumentTypeId(request.documentTypeId());
        document.setSender(request.sender());
        document.setCreatedBy(RequestContext.userId().orElse(null));

        boolean duplicate = existing.isPresent();
        if (duplicate) {
            Document original = existing.get();
            document.setProcessingStatus(Document.ProcessingStatus.DUPLICATE);
            document.setDuplicateOfDocumentId(original.getId());
            log.info("Document {} is a duplicate of {}", document.getId(), original.getId());
        }
        else if (scan.requiresReview()) {
            // A structurally implausible file (for example an archive with an extreme expansion
            // ratio) is never processed automatically; a human decides what to do with it.
            document.setProcessingStatus(Document.ProcessingStatus.REVIEW_REQUIRED);
            document.setReviewStatus(Document.ReviewStatus.PENDING);
        }
        documents.save(document);

        DocumentBlob blob = new DocumentBlob(UUID.randomUUID(), tenantId, stored.storageKey(),
                stored.backend(), stored.sizeBytes(), stored.contentHash(), declaredType);
        // The persisted status reflects the malware check, because that is the question an operator
        // asks about a stored file. "No scanner configured" is recorded as SKIPPED and a scanner
        // failure as FAILED - neither of them is ever reported as CLEAN.
        switch (malware.verdict()) {
            case CLEAN -> blob.markClean();
            case FAILED -> blob.markScanFailed(malware.details());
            case NOT_CHECKED -> blob.markScanSkipped();
            case INFECTED -> blob.markInfected();
        }
        blob.setScanDetails(scanDetails(scan, malware));
        blobs.save(blob);

        DocumentVersion version = new DocumentVersion(UUID.randomUUID(), document.getId(), tenantId, 1,
                blob.getId(), stored.contentHash(), stored.sizeBytes(), declaredType, originalFilename);
        versions.save(version);

        DocumentSource source = new DocumentSource(UUID.randomUUID(), document.getId(), tenantId,
                document.getSource());
        source.setRawMetadata(Map.of("declaredContentType", String.valueOf(file.getContentType())));
        sources.save(source);

        // The upload path already performed three pipeline steps; recording them keeps the pipeline
        // history complete instead of starting with a gap.
        pipeline.recordCompletedStep(document.getId(), version.getId(),
                DocumentProcessingStep.Step.SECURITY_SCAN,
                Map.of("structuralVerdict", scan.verdict().name(),
                        "malwareVerdict", malware.verdict().name(),
                        "malwareEngine", malware.engine()));
        pipeline.recordCompletedStep(document.getId(), version.getId(), DocumentProcessingStep.Step.HASH,
                Map.of("contentHash", stored.contentHash(), "algorithm", "SHA-256"));
        pipeline.recordCompletedStep(document.getId(), version.getId(),
                DocumentProcessingStep.Step.DUPLICATE_CHECK,
                Map.of("duplicate", duplicate,
                        "originalDocumentId", duplicate ? String.valueOf(document.getDuplicateOfDocumentId()) : ""));

        outbox.publish(new DomainEvent(
                duplicate ? "document.duplicate_detected" : "document.received",
                "documents", document.getId(), tenantId,
                Map.of("documentId", document.getId().toString(),
                        "contentHash", stored.contentHash(),
                        "sizeBytes", stored.sizeBytes(),
                        "mimeType", declaredType,
                        "duplicate", duplicate)));

        auditService.record(AuditService.AuditEntry
                .builder(AuditEvent.Category.DOCUMENT, duplicate ? "DOCUMENT_DUPLICATE" : "DOCUMENT_RECEIVED")
                .tenant(tenantId)
                .entity("documents", document.getId(), originalFilename)
                .description(duplicate
                        ? "Duplicate of document " + document.getDuplicateOfDocumentId()
                        : "Document received from " + document.getSource())
                .changes(Map.of("sizeBytes", stored.sizeBytes(), "mimeType", declaredType))
                .build());

        return new ReceivedDocument(document, duplicate, document.getDuplicateOfDocumentId());
    }

    @Transactional(readOnly = true)
    public Document require(UUID documentId) {
        UUID tenantId = RequestContext.tenantId()
                .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, "No tenant in context"));
        // The tenant is part of the lookup, so an identifier from another tenant simply does not
        // exist from this session's point of view (spec §3.2).
        return documents.findByIdAndTenantIdAndDeletedAtIsNull(documentId, tenantId)
                .orElseThrow(() -> ApiException.notFound("Document"));
    }

    @Transactional(readOnly = true)
    public Page<Document> list(String query, java.util.Collection<Document.ProcessingStatus> statuses,
                               java.util.Collection<Document.Source> sources, Pageable pageable) {
        return list(query, statuses, sources, null, null, pageable);
    }

    /** @param category a document type category, {@code UNSORTED} for documents without a type, or null for all */
    public Page<Document> list(String query, java.util.Collection<Document.ProcessingStatus> statuses,
                               java.util.Collection<Document.Source> sources, String category, String typeCode,
                               Pageable pageable) {
        UUID tenantId = RequestContext.tenantId()
                .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, "No tenant in context"));
        // Never pass null here: see the note on DocumentRepository.search.
        return documents.search(tenantId, query == null ? "" : query.trim(),
                statuses == null || statuses.isEmpty()
                        ? java.util.EnumSet.allOf(Document.ProcessingStatus.class) : statuses,
                sources == null || sources.isEmpty()
                        ? java.util.EnumSet.allOf(Document.Source.class) : sources,
                category == null ? "" : category, typeCode == null ? "" : typeCode,
                pageable);
    }

    /** Documents per category, for the tabs of the document list. */
    @Transactional(readOnly = true)
    public java.util.Map<String, Long> countByCategory() {
        UUID tenantId = RequestContext.tenantId()
                .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, "No tenant in context"));
        java.util.Map<String, Long> counts = new java.util.LinkedHashMap<>();
        for (Object[] row : documents.countByCategory(tenantId)) {
            counts.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        return counts;
    }

    /** Opens the bytes of the current version for download. */
    @Transactional(readOnly = true)
    public Download openContent(UUID documentId) {
        Document document = require(documentId);
        DocumentVersion version = versions.findFirstByDocumentIdOrderByVersionDesc(document.getId())
                .orElseThrow(() -> ApiException.notFound("Document version"));
        DocumentBlob blob = blobs.findById(version.getBlobId())
                .orElseThrow(() -> ApiException.notFound("Stored file"));

        if (blob.getScanStatus() == DocumentBlob.ScanStatus.INFECTED) {
            // Infected content is never handed out, not even to its owner.
            throw new ApiException(ErrorCode.FILE_REJECTED, "The stored file failed the security check");
        }

        auditService.record(AuditService.AuditEntry
                .builder(AuditEvent.Category.DOCUMENT_ACCESS, "DOCUMENT_DOWNLOADED")
                .tenant(document.getTenantId())
                .entity("documents", document.getId(), document.getOriginalFilename())
                .build());

        return new Download(document.getOriginalFilename(), document.getMimeType(),
                blob.getSizeBytes(), blobStore.retrieve(blob.getStorageKey()));
    }

    /** Moves a document along the pipeline; an impossible transition is refused (spec §6.3). */
    @Transactional
    public Document changeStatus(UUID documentId, Document.ProcessingStatus target, String reason) {
        Document document = require(documentId);
        DocumentStatusMachine.requireTransition(document.getProcessingStatus(), target);
        Document.ProcessingStatus previous = document.getProcessingStatus();
        document.setProcessingStatus(target);
        documents.save(document);

        auditService.record(AuditService.AuditEntry
                .builder(AuditEvent.Category.DOCUMENT, "DOCUMENT_STATUS_CHANGED")
                .tenant(document.getTenantId())
                .entity("documents", document.getId(), document.getOriginalFilename())
                .description("Status changed from %s to %s".formatted(previous, target))
                .changes(Map.of("from", previous.name(), "to", target.name(),
                        "reason", reason == null ? "" : reason))
                .build());
        return document;
    }

    /** Documents waiting for a human decision, used by the review centre (spec §1.1 п.7). */
    @Transactional(readOnly = true)
    public Page<Document> reviewQueue(Pageable pageable) {
        UUID tenantId = RequestContext.tenantId()
                .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, "No tenant in context"));
        return documents.findByTenantIdAndProcessingStatusAndDeletedAtIsNull(tenantId,
                Document.ProcessingStatus.REVIEW_REQUIRED, pageable);
    }

    /** Record of the received file in the object store. */
    public record Download(String filename, String contentType, long sizeBytes, InputStream content) {
    }

    /** Runs the malware check over the bytes that were just stored. */
    private MalwareScanner.Scan scanForMalware(MultipartFile file, String contentType,
                                               BlobStore.StoredBlob stored) {
        if (!malwareScanner.isAvailable()) {
            return MalwareScanner.Scan.notChecked(malwareScanner.name());
        }
        try (InputStream content = blobStore.retrieve(stored.storageKey())) {
            return malwareScanner.scan(content, stored.sizeBytes(), file.getOriginalFilename());
        }
        catch (IOException ex) {
            // The file stays quarantined: an unreadable file was not checked.
            return MalwareScanner.Scan.failed(malwareScanner.name(), "The stored file could not be read");
        }
    }

    /** Diagnostics of both checks; never contains file content. */
    private static Map<String, Object> scanDetails(FileScanResult structural, MalwareScanner.Scan malware) {
        Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("structuralVerdict", structural.verdict().name());
        details.put("detectedContentType", String.valueOf(structural.detectedContentType()));
        details.put("malwareVerdict", malware.verdict().name());
        details.put("malwareEngine", malware.engine());
        details.put("malwareChecked", malware.verdict() == MalwareScanner.Verdict.CLEAN);
        if (malware.signature() != null) {
            details.put("malwareSignature", malware.signature());
        }
        if (structural.details() != null) {
            details.put("archiveEntries", structural.details().archiveEntries());
            details.put("archiveUncompressedBytes", structural.details().archiveUncompressedBytes());
            details.put("archiveCompressionRatio", structural.details().compressionRatio());
        }
        return details;
    }

    /** Runs the structural security check over the bytes that were just stored. */
    private FileScanResult scanStoredContent(UUID tenantId, MultipartFile file, String contentType,
                                             BlobStore.StoredBlob stored) {
        try (InputStream content = blobStore.retrieve(stored.storageKey())) {
            return securityScanner.scan(content, stored.sizeBytes(), file.getOriginalFilename(), contentType);
        }
        catch (IOException ex) {
            throw new de.buerokratiecopilot.shared.storage.BlobStorageException(
                    "The stored file could not be checked", ex);
        }
    }

    private BlobStore.StoredBlob storeBlob(UUID tenantId, MultipartFile file, String contentType) {
        try (InputStream input = file.getInputStream()) {
            return blobStore.store(tenantId, input, file.getSize(), contentType);
        }
        catch (IOException ex) {
            throw new de.buerokratiecopilot.shared.storage.BlobStorageException(
                    "The uploaded file could not be read", ex);
        }
    }

    /**
     * Removes directory components from a client-supplied file name.
     *
     * <p>The name is only used for display; the storage key is generated independently. This is still
     * sanitised because a path would travel into exports, e-mail subjects and download headers
     * (spec §21.2).</p>
     */
    static String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "unbenannt";
        }
        String cleaned = filename.replace('\\', '/');
        int slash = cleaned.lastIndexOf('/');
        if (slash >= 0) {
            cleaned = cleaned.substring(slash + 1);
        }
        cleaned = cleaned.replaceAll("[\\p{Cntrl}]", "").trim();
        if (cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..")) {
            return "unbenannt";
        }
        return cleaned.length() > 400 ? cleaned.substring(0, 400) : cleaned;
    }

    private static String normalizeContentType(String declared, String filename) {
        if (declared != null && !declared.isBlank() && !"application/octet-stream".equals(declared)) {
            return declared.length() > 150 ? declared.substring(0, 150) : declared;
        }
        String lower = filename.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".tif") || lower.endsWith(".tiff")) {
            return "image/tiff";
        }
        if (lower.endsWith(".txt")) {
            return "text/plain";
        }
        if (lower.endsWith(".csv")) {
            return "text/csv";
        }
        if (lower.endsWith(".xml")) {
            return "application/xml";
        }
        if (lower.endsWith(".eml")) {
            return "message/rfc822";
        }
        return "application/octet-stream";
    }
}
