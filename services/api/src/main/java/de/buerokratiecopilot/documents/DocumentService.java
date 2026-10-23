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
import de.buerokratiecopilot.documents.domain.BelegStatus;
import de.buerokratiecopilot.documents.domain.BelegStatusMachine;
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
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final de.buerokratiecopilot.billing.PlanFeatures planFeatures;

    private final de.buerokratiecopilot.demo.DemoGuard demoGuard;

    public DocumentService(DocumentRepository documents, DocumentBlobRepository blobs,
                           DocumentVersionRepository versions, DocumentSourceRepository sources,
                           BlobStore blobStore, OutboxWriter outbox, AuditService auditService,
                           BurokratieProperties properties, FileSecurityScanner securityScanner,
                           MalwareScanner malwareScanner, DocumentProcessingPipeline pipeline,
                           org.springframework.jdbc.core.JdbcTemplate jdbc,
                           de.buerokratiecopilot.billing.PlanFeatures planFeatures,
                           de.buerokratiecopilot.demo.DemoGuard demoGuard) {
        this.demoGuard = demoGuard;
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
        this.jdbc = jdbc;
        this.planFeatures = planFeatures;
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
        if (!duplicate) {
            enforceMonthlyAnalyses(tenantId);
        }
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

        if (duplicate && document.getDuplicateOfDocumentId() != null) {
            // The copy is related to its original, with the reason: the same bytes.
            documents.flush();
            jdbc.update("""
                    insert into document_relations (tenant_id, source_document_id, target_document_id, relation_type,
                                                    confidence, status, evidence)
                    values (?, ?, ?, 'DUPLICATE', 1.0, 'CONFIRMED', jsonb_build_object('rule', 'identical content hash',
                                                                                      'contentHash', ?::text))
                    on conflict do nothing
                    """, tenantId, document.getId(), document.getDuplicateOfDocumentId(), stored.contentHash());
        }

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
        return list(query, statuses, sources, null, null, null, null, pageable);
    }

    /** @param category a document type category, {@code UNSORTED} for documents without a type, or null for all */
    @Transactional(readOnly = true)
    public Page<Document> list(String query, java.util.Collection<Document.ProcessingStatus> statuses,
                               java.util.Collection<Document.Source> sources, String category, String typeCode,
                               Pageable pageable) {
        return list(query, statuses, sources, category, typeCode, null, null, pageable);
    }

    /**
     * The queue's list query (Kanzlei edition, §5.1).
     *
     * @param category    a document type category, {@code UNSORTED} for documents without a type, or null for all
     * @param belegStatus where the voucher stands in the office queue, or null for every state
     * @param mandantId   a client id, {@code NONE} for vouchers without a client, or null/blank for all. The
     *                    value is parsed here rather than in the controller so the API and any future caller
     *                    share one answer for "Ohne Mandant".
     */
    @Transactional(readOnly = true)
    public Page<Document> list(String query, java.util.Collection<Document.ProcessingStatus> statuses,
                               java.util.Collection<Document.Source> sources, String category, String typeCode,
                               BelegStatus belegStatus, String mandantId, Pageable pageable) {
        UUID tenantId = RequestContext.tenantId()
                .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, "No tenant in context"));
        MandantFilter mandant = MandantFilter.parse(mandantId);
        // Never pass null here: see the note on DocumentRepository.search.
        return documents.search(tenantId, query == null ? "" : query.trim(),
                statuses == null || statuses.isEmpty()
                        ? java.util.EnumSet.allOf(Document.ProcessingStatus.class) : statuses,
                sources == null || sources.isEmpty()
                        ? java.util.EnumSet.allOf(Document.Source.class) : sources,
                belegStatus == null
                        ? java.util.EnumSet.allOf(BelegStatus.class) : java.util.EnumSet.of(belegStatus),
                category == null ? "" : category, typeCode == null ? "" : typeCode,
                mandant.filter(), mandant.withoutMandant(), mandant.id(), pageable);
    }

    /**
     * The client filter of the queue: everybody, the vouchers without a client, or one client.
     *
     * <p>{@code NONE} is a value of the filter, not an absent UUID — "Ohne Mandant" is what the office
     * works through first (§5.2). The all-zero UUID stands in for "no particular client" so the query
     * never binds an untyped {@code null}.</p>
     */
    private record MandantFilter(boolean filter, boolean withoutMandant, UUID id) {

        private static final UUID NONE = new UUID(0L, 0L);
        private static final MandantFilter ANY = new MandantFilter(false, false, NONE);

        static MandantFilter parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return ANY;
            }
            String value = raw.trim();
            if ("NONE".equalsIgnoreCase(value)) {
                return new MandantFilter(true, true, NONE);
            }
            try {
                return new MandantFilter(true, false, UUID.fromString(value));
            }
            catch (IllegalArgumentException ex) {
                throw new ApiException(ErrorCode.INVALID_REQUEST,
                        "mandantId muss eine Mandanten-Kennung oder NONE sein.");
            }
        }
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

    /**
     * Deletes a document so that it disappears from every list, search and calendar. The row and the stored file
     * are kept (marked deleted), so the audit trail stays complete and an administrator can still restore it.
     * What the platform made of the document goes with it: open tasks it created, its calendar entries and its
     * pending reviews. Finished tasks, cases and contract cards stay, because they record work that happened.
     * A document on legal hold is refused.
     */
    @Transactional
    public void delete(UUID documentId, String reason) {
        Document document = require(documentId);
        if (!document.isDeletable()) {
            throw new ApiException(ErrorCode.CONFLICT, "The document is under legal hold and cannot be deleted");
        }
        document.setDeletedAt(java.time.Instant.now());
        documents.saveAndFlush(document);
        int tasks = jdbc.update("""
                update tasks set deleted_at = now()
                where source_document_id = ? and source = 'DOCUMENT' and deleted_at is null
                  and status not in ('DONE', 'CANCELLED')
                """, documentId);
        int events = jdbc.update(
                "update calendar_events set deleted_at = now() where source_document_id = ? and deleted_at is null",
                documentId);
        jdbc.update("""
                update document_reviews set status = 'CANCELLED', decided_at = now()
                where document_id = ? and status in ('PENDING', 'IN_REVIEW')
                """, documentId);

        auditService.record(AuditService.AuditEntry
                .builder(AuditEvent.Category.DOCUMENT, "DOCUMENT_DELETED")
                .tenant(document.getTenantId())
                .entity("documents", document.getId(), document.getOriginalFilename())
                .description("Document deleted")
                .changes(Map.of("reason", reason == null ? "" : reason, "tasksRemoved", tasks,
                        "calendarEventsRemoved", events))
                .build());
    }

    /** The Free plan analyses a few documents per month; a document that is only a copy of another costs nothing. */
    private void enforceDemoLimit(UUID tenantId) {
        if (demoGuard.isDemo(tenantId)) {
            Integer count = jdbc.queryForObject("select count(*) from documents where tenant_id = ? and deleted_at is null",
                    Integer.class, tenantId);
            if (count != null && count >= de.buerokratiecopilot.demo.DemoGuard.MAX_DOCUMENTS) {
                throw ApiException.conflict("In der Demo sind höchstens " + de.buerokratiecopilot.demo.DemoGuard.MAX_DOCUMENTS
                        + " Dokumente möglich.");
            }
        }
    }

    private void enforceMonthlyAnalyses(UUID tenantId) {
        enforceDemoLimit(tenantId);
        Integer limit = planFeatures.analysesPerMonth(tenantId);
        if (limit == null) {
            return;
        }
        Integer used = jdbc.queryForObject("""
                select count(*) from documents
                where tenant_id = ? and received_at >= date_trunc('month', now())
                  and processing_status <> 'DUPLICATE'
                """, Integer.class, tenantId);
        if (used != null && used >= limit) {
            throw new ApiException(ErrorCode.PLAN_LIMIT_REACHED,
                    "Der Free-Tarif umfasst " + limit + " Analysen pro Monat; sie sind aufgebraucht");
        }
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

    /**
     * Moves a voucher through the office queue (§8.1).
     *
     * <p>The transition is validated, not forced: the specification's statuses are a promise about what has been
     * done, and a screen that lets NEU jump to EXPORTIERT turns the promise into decoration. Every change is
     * audited with both ends, because "who moved this voucher and when" is the question asked after a mistake.</p>
     */
    @org.springframework.transaction.annotation.Transactional
    public Document changeBelegStatus(java.util.UUID id, BelegStatus target) {
        Document document = require(id);
        BelegStatus current = document.getBelegStatus();
        // §5.2 and §11.7: the one rule the whole client routing exists for. A voucher whose client is not known
        // does not leave the office in a DATEV batch - it waits in the queue as "Ohne Mandant" until a person
        // says whose it is. The alternative is sending client A's paperwork to client B's bookkeeping, which is
        // the silent mistake this specification forbids more strongly than any other.
        if (target == BelegStatus.EXPORTIERT && document.getMandantId() == null) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Der Beleg hat keinen Mandanten und kann nicht exportiert werden. "
                            + "Bitte zuerst den Mandanten zuordnen.");
        }
        if (!BelegStatusMachine.canMove(current, target)) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Ein Beleg kann nicht von " + current + " nach " + target + " wechseln.");
        }
        if (current == target) {
            return document;
        }
        document.setBelegStatus(target);
        Document saved = documents.save(document);
        auditService.record(AuditService.AuditEntry
                .builder(AuditEvent.Category.DOCUMENT, "BELEG_STATUS_CHANGED")
                .tenant(document.getTenantId())
                .entity("documents", document.getId(), document.getOriginalFilename())
                .description("Beleg-Status: " + current + " → " + target)
                .build());
        return saved;
    }

    /**
     * Assigns a voucher to a client, or takes it away when {@code mandantId} is null (§5.2).
     *
     * <p>The specification requires two things here and both are cheap to get wrong. A re-assignment is allowed -
     * the first guess of a tired person is not a life sentence - and it is audited with both ends, because
     * "who moved this voucher from client A to client B" is the question asked when the books do not match.
     * And an assignment made by a person is final in a way an automatic one is not: it is not silent.</p>
     */
    @org.springframework.transaction.annotation.Transactional
    public Document assignMandant(java.util.UUID id, java.util.UUID mandantId) {
        return assignMandant(id, mandantId, null);
    }

    /**
     * The same assignment with a note on where it came from - {@code AUTO} for the routing service (§5.2).
     *
     * <p>The audit trail must answer "did a person decide this, or did the platform?" - a wrong automatic
     * assignment is reviewed differently from a wrong click. The note is written into the description rather
     * than into a second event, so the assignment and its reason are one entry and cannot drift apart.</p>
     */
    @org.springframework.transaction.annotation.Transactional
    public Document assignMandant(java.util.UUID id, java.util.UUID mandantId, String origin) {
        Document document = require(id);
        java.util.UUID previous = document.getMandantId();
        if (java.util.Objects.equals(previous, mandantId)) {
            return document;
        }
        document.setMandantId(mandantId);
        Document saved = documents.save(document);
        String note = origin == null || origin.isBlank() ? "" : " [" + origin.strip() + "]";
        auditService.record(AuditService.AuditEntry
                .builder(AuditEvent.Category.DOCUMENT, "DOCUMENT_MANDANT_ASSIGNED")
                .tenant(document.getTenantId())
                .entity("documents", document.getId(), document.getOriginalFilename())
                .description(previous == null
                        ? "Mandant zugeordnet: " + mandantId + note
                        : "Mandant geändert: " + previous + " → " + mandantId + note)
                .build());
        return saved;
    }
}
