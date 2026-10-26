package de.buerokratiecopilot.documents.web;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.Size;

import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import de.buerokratiecopilot.documents.DocumentService;
import de.buerokratiecopilot.documents.domain.Document;

/**
 * Document API (spec §20: {@code /documents}).
 *
 * <p>Every method is guarded by a permission of the RBAC catalogue, which is resolved from the
 * authenticated membership — a role that cannot view documents gets 403 before any query runs.</p>
 */
@RestController
@RequestMapping("/api/v1/documents")
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
        type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
@Tag(name = "Documents", description = "Upload, list, inspect and download documents")
public class DocumentController {

    private final DocumentService documentService;
    private final de.buerokratiecopilot.documents.pipeline.DocumentProcessingPipeline pipeline;
    private final de.buerokratiecopilot.documents.DocumentTypeRepository documentTypes;
    private final de.buerokratiecopilot.documents.classification.DocumentResortService resorter;
    private final de.buerokratiecopilot.documents.UploaderNames uploaders;
    private final de.buerokratiecopilot.documents.integrity.DocumentIntegrityService integrity;
    private final de.buerokratiecopilot.expenses.ExpenseCategoryRepository expenseCategories;
    private final de.buerokratiecopilot.expenses.ExpenseCategorizationService expenseCategorization;

    public DocumentController(DocumentService documentService,
                              de.buerokratiecopilot.documents.pipeline.DocumentProcessingPipeline pipeline,
                              de.buerokratiecopilot.documents.DocumentTypeRepository documentTypes,
                              de.buerokratiecopilot.documents.classification.DocumentResortService resorter,
                              de.buerokratiecopilot.documents.UploaderNames uploaders,
                              de.buerokratiecopilot.documents.integrity.DocumentIntegrityService integrity,
                              de.buerokratiecopilot.expenses.ExpenseCategoryRepository expenseCategories,
                              de.buerokratiecopilot.expenses.ExpenseCategorizationService expenseCategorization) {
        this.expenseCategories = expenseCategories;
        this.expenseCategorization = expenseCategorization;
        this.uploaders = uploaders;
        this.resorter = resorter;
        this.documentService = documentService;
        this.pipeline = pipeline;
        this.documentTypes = documentTypes;
        this.integrity = integrity;
    }

    /**
     * The expense category of a document, with the names and the DATEV accounts a screen needs.
     *
     * <p>Kept as a nested object in the document answer: a list of documents must not have to ask again per
     * row, and the folder is only meaningful together with the category it came from.</p>
     */
    public record ExpenseCategorySummary(UUID id, String code, java.util.Map<String, String> names,
                                         java.util.Map<String, String> descriptions,
                                         java.util.Map<String, String> warnings,
                                         String datevSkr03, String datevSkr04, String folderTemplate,
                                         boolean businessExpense, boolean requiresReview) {

        static ExpenseCategorySummary of(de.buerokratiecopilot.expenses.domain.ExpenseCategory category) {
            if (category == null) {
                return null;
            }
            return new ExpenseCategorySummary(category.getId(), category.getCode(),
                    localized(category, category::nameIn), localized(category, category::descriptionIn),
                    localized(category, category::warningIn), category.getDatevSkr03(),
                    category.getDatevSkr04(), category.getFolderPath(), category.isBusinessExpense(),
                    category.isRequiresReview());
        }

        private static java.util.Map<String, String> localized(
                de.buerokratiecopilot.expenses.domain.ExpenseCategory category,
                java.util.function.Function<String, String> value) {
            java.util.Map<String, String> answer = new java.util.LinkedHashMap<>();
            for (String language : java.util.List.of("de", "en", "ru")) {
                answer.put(language, value.apply(language));
            }
            return answer;
        }
    }

    /** Summary of a document as returned by the API. */
    public record DocumentResponse(
            UUID id,
            String originalFilename,
            String mimeType,
            long sizeBytes,
            String contentHash,
            int version,
            String processingStatus,
            String reviewStatus,
            String confidentiality,
            String source,
            String documentNumber,
            java.time.LocalDate documentDate,
            UUID documentTypeId,
            UUID duplicateOfDocumentId,
            boolean legalHold,
            Instant receivedAt,
            Instant updatedAt,
            String documentTypeCode,
            String category,
            java.util.Map<String, String> documentTypeNames,
            UUID partyId,
            String sender,
            java.util.Map<String, Object> senderDetails,
            UUID senderContactId,
            String senderSource,
            UUID uploadedBy,
            String uploadedByName,
            ExpenseCategorySummary expenseCategory,
            String folderPath,
            Double expenseCategoryConfidence,
            String expenseCategorySource,
            String expenseCategoryRationale) {

        static DocumentResponse from(Document document) {
            return from(document, null, null);
        }

        /** With the type spelled out: the code, the category and the name in every language. */
        static DocumentResponse from(Document document,
                                     de.buerokratiecopilot.documents.domain.DocumentType type) {
            return from(document, type, null);
        }

        /**
         * The full answer: document type, expense category and the folder it lies in.
         *
         * <p>The category is passed instead of being read from the document, so a list can resolve all rows
         * with one query instead of one per row.</p>
         */
        static DocumentResponse from(Document document,
                                     de.buerokratiecopilot.documents.domain.DocumentType type,
                                     de.buerokratiecopilot.expenses.domain.ExpenseCategory expenseCategory) {
            return new DocumentResponse(document.getId(), document.getOriginalFilename(),
                    document.getMimeType(), document.getSizeBytes(), document.getContentHash(),
                    document.getCurrentVersion(), document.getProcessingStatus().name(),
                    document.getReviewStatus().name(), document.getConfidentiality().name(),
                    document.getSource().name(), document.getDocumentNumber(),
                    document.getDocumentDate(), document.getDocumentTypeId(),
                    document.getDuplicateOfDocumentId(), document.isLegalHold(),
                    document.getReceivedAt(), document.getUpdatedAt(),
                    type == null ? null : type.getCode(), type == null ? null : type.getCategory(),
                    type == null ? null : java.util.Map.of("de", type.nameIn("de"), "en", type.nameIn("en"),
                            "ru", type.nameIn("ru")),
                    document.getPartyId(), document.getSender(), document.getSenderDetails(),
                    document.getSenderContactId(), document.getSenderSource(), document.getCreatedBy(), null,
                    ExpenseCategorySummary.of(expenseCategory), document.getFolderPath(),
                    document.getExpenseCategoryConfidence() == null ? null
                            : document.getExpenseCategoryConfidence().doubleValue(),
                    document.getExpenseCategorySource(), document.getExpenseCategoryRationale());
        }

        /** The same answer with the name of the person who uploaded the document. */
        DocumentResponse withUploader(java.util.Map<UUID, String> names) {
            return new DocumentResponse(id, originalFilename, mimeType, sizeBytes, contentHash, version, processingStatus,
                    reviewStatus, confidentiality, source, documentNumber, documentDate, documentTypeId, duplicateOfDocumentId,
                    legalHold, receivedAt, updatedAt, documentTypeCode, category, documentTypeNames, partyId, sender,
                    senderDetails, senderContactId, senderSource, uploadedBy, uploadedBy == null ? null : names.get(uploadedBy),
                    expenseCategory, folderPath, expenseCategoryConfidence, expenseCategorySource,
                    expenseCategoryRationale);
        }
    }

    public record UploadResponse(DocumentResponse document, boolean duplicate, UUID duplicateOf,
                                 String nextStep) {
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('documents.create')")
    @Operation(summary = "Receive a document into the inbox")
    public ResponseEntity<UploadResponse> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) UUID workspaceId,
            @RequestParam(required = false) UUID documentTypeId,
            @RequestParam(required = false) String sender) {

        DocumentService.ReceivedDocument received = documentService.receive(
                new DocumentService.UploadRequest(file, workspaceId, documentTypeId,
                        Document.Source.UPLOAD, sender));

        String nextStep = received.duplicate()
                ? "REVIEW_DUPLICATE"
                : "PROCESSING_PENDING";

        return ResponseEntity.status(HttpStatus.CREATED).body(new UploadResponse(
                DocumentResponse.from(received.document(), documentTypeOf(received.document()),
                        expenseCategoryOf(received.document())).withUploader(namesFor(received.document())),
                received.duplicate(), received.duplicateOf(), nextStep));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('documents.view')")
    @Operation(summary = "List documents with search, status (repeatable) and source (repeatable) filters")
    public ResponseEntity<Page<DocumentResponse>> list(
            @RequestParam(required = false) @Size(max = 200) String q,
            @RequestParam(required = false) java.util.List<Document.ProcessingStatus> status,
            @RequestParam(required = false) java.util.List<Document.Source> source,
            @RequestParam(required = false) @Size(max = 40) String category,
            @RequestParam(required = false) @Size(max = 80) String type,
            @RequestParam(required = false) @Size(max = 40) String party,
            @PageableDefault(size = 25, sort = "receivedAt", direction = org.springframework.data.domain.Sort.Direction.DESC) Pageable pageable) {

        // The types are few and shared by every account; reading them once per request is cheaper than a join per row.
        java.util.Map<java.util.UUID, de.buerokratiecopilot.documents.domain.DocumentType> byId =
                new java.util.HashMap<>();
        documentTypes.findAll().forEach(documentType -> byId.put(documentType.getId(), documentType));
        java.util.Map<java.util.UUID, de.buerokratiecopilot.expenses.domain.ExpenseCategory> expenseById =
                new java.util.HashMap<>();
        expenseCategories.findAll().forEach(expenseCategory -> expenseById.put(expenseCategory.getId(), expenseCategory));
        Page<Document> documents = documentService.list(q, status, source, category, type, party, pageable);
        java.util.Map<UUID, String> names = uploaders.namesOf(documents.getContent().stream().map(Document::getCreatedBy).toList());
        Page<DocumentResponse> page = documents
                .map(document -> DocumentResponse.from(document,
                        document.getDocumentTypeId() == null ? null : byId.get(document.getDocumentTypeId()),
                        document.getExpenseCategoryId() == null ? null : expenseById.get(document.getExpenseCategoryId()))
                        .withUploader(names));
        return ResponseEntity.ok(page);
    }

    private java.util.Map<UUID, String> namesFor(Document document) {
        return document.getCreatedBy() == null ? java.util.Map.of() : uploaders.namesOf(java.util.List.of(document.getCreatedBy()));
    }

    /** One entry of the catalogue of document types the platform recognises. */
    public record CatalogueEntry(String code, String category, java.util.Map<String, String> names, String audiences) {
    }

    @GetMapping("/types")
    @PreAuthorize("hasAuthority('documents.view')")
    @Operation(summary = "Every document type the platform recognises, with the kinds of cabinet it is meant for")
    public java.util.List<CatalogueEntry> catalogue() {
        return documentTypes.findAll().stream()
                .filter(de.buerokratiecopilot.documents.domain.DocumentType::isActive)
                .sorted(java.util.Comparator
                        .comparing(de.buerokratiecopilot.documents.domain.DocumentType::getCategory)
                        .thenComparing(type -> type.nameIn("de")))
                .map(type -> new CatalogueEntry(type.getCode(), type.getCategory(),
                        java.util.Map.of("de", type.nameIn("de"), "en", type.nameIn("en"), "ru", type.nameIn("ru")),
                        type.getAudiences()))
                .toList();
    }

    @GetMapping("/categories")
    @PreAuthorize("hasAuthority('documents.view')")
    @Operation(summary = "How many documents lie in each category (UNSORTED: no confirmed type yet)")
    public ResponseEntity<java.util.Map<String, Long>> categories() {
        return ResponseEntity.ok(documentService.countByCategory());
    }

    /**
     * Whether the stored file still matches the hash it was recorded with.
     *
     * <p>§22 asks for an archive whose integrity can be shown. The hashes were written and never compared with
     * the bytes, so the answer was a claim rather than a check.</p>
     */
    @GetMapping("/{id}/integrity")
    @PreAuthorize("hasAnyAuthority('documents.view','documents.review')")
    @Operation(summary = "Recomputes the hash of the stored file and compares it with the recorded one")
    public java.util.Map<String, Object> integrity(@PathVariable UUID id) {
        var result = integrity.verify(de.buerokratiecopilot.shared.context.RequestContext.tenantId().orElseThrow(), id);
        java.util.Map<String, Object> answer = new java.util.LinkedHashMap<>();
        answer.put("documentId", result.documentId().toString());
        answer.put("version", result.version());
        answer.put("expected", result.expected());
        answer.put("actual", result.actual());
        answer.put("matches", result.matches());
        answer.put("note", result.message());
        return answer;
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('documents.view')")
    @Operation(summary = "Read one document; an identifier of another tenant is answered with 404")
    public ResponseEntity<DocumentResponse> get(@PathVariable UUID id) {
        Document document = documentService.require(id);
        // The type is named in the answer, as in the list: a screen that opens one document must not have to ask again.
        return ResponseEntity.ok(DocumentResponse.from(document,
                document.getDocumentTypeId() == null ? null : documentTypes.findById(document.getDocumentTypeId()).orElse(null),
                expenseCategoryOf(document))
                .withUploader(namesFor(document)));
    }

    /**
     * Types a browser shows itself and that cannot run code in the page. HTML, SVG and anything else a browser
     * would interpret stay downloads: a stored file is served from the application's own origin, so showing it
     * "inline" must never be possible for a format that can carry a script.
     */
    private static final java.util.Set<String> SHOWN_IN_BROWSER = java.util.Set.of("application/pdf", "image/png",
            "image/jpeg", "image/gif", "image/webp", "text/plain");

    @GetMapping("/{id}/content")
    @PreAuthorize("hasAuthority('documents.view')")
    @Operation(summary = "The stored file of the current version: shown in the browser when it can be, "
            + "or downloaded with ?download=true")
    public ResponseEntity<InputStreamResource> content(@PathVariable UUID id,
                                                       @RequestParam(defaultValue = "false") boolean download)
            throws IOException {
        DocumentService.Download file = documentService.openContent(id);
        InputStream content = file.content();
        String type = file.contentType() == null ? "" : file.contentType().toLowerCase(java.util.Locale.ROOT);
        boolean inline = !download && SHOWN_IN_BROWSER.contains(type.split(";")[0].trim());
        // The file name is sanitised before it reaches this header, so a crafted name cannot inject a second
        // header (spec §21.2). The name is also sent in its UTF-8 form so umlauts and Cyrillic survive.
        String safeName = file.filename().replace("\"", "").replaceAll("[\\r\\n]", " ");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition((inline
                ? org.springframework.http.ContentDisposition.inline()
                : org.springframework.http.ContentDisposition.attachment())
                .filename(safeName, java.nio.charset.StandardCharsets.UTF_8).build());
        headers.add("X-Content-Type-Options", "nosniff");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.sizeBytes())
                .headers(headers)
                .body(new InputStreamResource(content));
    }

    @PostMapping("/{id}/status")
    @PreAuthorize("hasAuthority('documents.review')")
    @Operation(summary = "Move a document along the processing pipeline")
    public ResponseEntity<DocumentResponse> changeStatus(@PathVariable UUID id,
                                                         @RequestParam Document.ProcessingStatus status,
                                                         @RequestParam(required = false) String reason) {
        Document updated = documentService.changeStatus(id, status, reason);
        return ResponseEntity.ok(DocumentResponse.from(updated, documentTypeOf(updated),
                expenseCategoryOf(updated)));
    }


    /**
     * Runs the stages that failed again, without repeating those that succeeded, and lets the document
     * continue. A stage that did not fail is left alone: repeating a finished OCR run would only spend time
     * to reach the same text.
     */
    @org.springframework.web.bind.annotation.DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('documents.delete')")
    @Operation(summary = "Delete a document together with the open work the platform created from it")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        documentService.delete(id, null);
        return ResponseEntity.noContent().build();
    }

    public record BulkDeleteRequest(java.util.List<UUID> ids) {
    }

    public record BulkDeleteResponse(int deleted, java.util.List<UUID> refused) {
    }

    /** Deletes many documents at once; one that cannot be deleted (legal hold, not found) is reported, not fatal. */
    @PostMapping("/bulk-delete")
    @PreAuthorize("hasAuthority('documents.delete')")
    @Operation(summary = "Delete several documents; the ones that cannot be deleted are listed as refused")
    public BulkDeleteResponse bulkDelete(@org.springframework.web.bind.annotation.RequestBody BulkDeleteRequest request) {
        java.util.List<UUID> ids = request.ids() == null ? java.util.List.of()
                : request.ids().stream().distinct().toList();
        if (ids.isEmpty() || ids.size() > 200) {
            throw new de.buerokratiecopilot.shared.error.ApiException(
                    de.buerokratiecopilot.shared.error.ErrorCode.INVALID_REQUEST, "Between 1 and 200 documents");
        }
        int deleted = 0;
        java.util.List<UUID> refused = new java.util.ArrayList<>();
        for (UUID id : ids) {
            try {
                documentService.delete(id, "bulk");
                deleted++;
            }
            catch (de.buerokratiecopilot.shared.error.ApiException ex) {
                refused.add(id);
            }
        }
        return new BulkDeleteResponse(deleted, refused);
    }

    public record TypeRequest(String typeCode) {
    }

    /**
     * A person puts a document into a drawer they chose. It is recorded as that person's decision (an approved
     * classification review that names the type), so later re-sorting by the rules leaves it alone.
     */
    @PostMapping("/{id}/type")
    @PreAuthorize("hasAuthority('documents.review')")
    @Operation(summary = "Set the document type by hand; the choice is the person's and is not undone by re-sorting")
    public ResponseEntity<DocumentResponse> setType(@PathVariable UUID id,
                                                    @org.springframework.web.bind.annotation.RequestBody TypeRequest request) {
        Document document = documentService.require(id);
        if (request == null || request.typeCode() == null || request.typeCode().isBlank()) {
            throw new de.buerokratiecopilot.shared.error.ApiException(
                    de.buerokratiecopilot.shared.error.ErrorCode.VALIDATION_FAILED, "A document type is required");
        }
        var type = documentTypes.findByCode(request.typeCode())
                .filter(de.buerokratiecopilot.documents.domain.DocumentType::isActive)
                .orElseThrow(() -> de.buerokratiecopilot.shared.error.ApiException.notFound("Document type"));
        Document filed = resorter.setByPerson(document, type, de.buerokratiecopilot.shared.context.CurrentUser.required());
        // The person answered the question about the type: processing goes on from where it waited, and a document with
        // no other open question becomes ready. Without this it stayed "in processing" for ever.
        pipeline.advance(id);
        Document fresh = documentService.require(id);
        return ResponseEntity.ok(DocumentResponse.from(fresh, type, expenseCategoryOf(fresh)));
    }

    /** What a caller sends to catalogue a document by hand. An empty body re-runs the rules. */
    public record CategorizeRequest(String categoryCode) {
    }

    public record CategorizeResponse(DocumentResponse document, boolean applied, boolean suggested,
                                     boolean manual, String message, String folderPath,
                                     java.util.List<String> evidence) {
    }

    /**
     * Catalogues a document immediately: either with the category the caller chose, or by re-running the
     * rules. The explicit call applies the best match even below the automatic threshold — the caller asked
     * for a decision now — but never invents a category when no rule matches.
     */
    @PostMapping("/{id}/categorize")
    @PreAuthorize("hasAuthority('documents.review')")
    @Operation(summary = "Catalogue a document as an expense: by the rules, or with the category the caller chose")
    public ResponseEntity<CategorizeResponse> categorize(
            @PathVariable UUID id,
            @org.springframework.web.bind.annotation.RequestBody(required = false) CategorizeRequest request) {
        var outcome = expenseCategorization.categorizeNow(id, request == null ? null : request.categoryCode(),
                de.buerokratiecopilot.shared.context.CurrentUser.required());
        Document fresh = documentService.require(id);
        var decision = outcome.decision();
        return ResponseEntity.ok(new CategorizeResponse(
                DocumentResponse.from(fresh,
                        fresh.getDocumentTypeId() == null ? null : documentTypes.findById(fresh.getDocumentTypeId()).orElse(null),
                        expenseCategoryOf(fresh)),
                outcome.applied(), outcome.suggested(), outcome.manual(), outcome.message(),
                decision == null ? null : decision.folderPath(),
                decision == null ? java.util.List.of() : decision.evidence()));
    }

    private de.buerokratiecopilot.expenses.domain.ExpenseCategory expenseCategoryOf(Document document) {
        return document.getExpenseCategoryId() == null ? null
                : expenseCategories.findById(document.getExpenseCategoryId()).orElse(null);
    }

    private de.buerokratiecopilot.documents.domain.DocumentType documentTypeOf(Document document) {
        return document.getDocumentTypeId() == null ? null
                : documentTypes.findById(document.getDocumentTypeId()).orElse(null);
    }

    @PostMapping("/resort")
    @PreAuthorize("hasAuthority('documents.review')")
    @Operation(summary = "Put the stored documents of this tenant in the drawers the current rules choose; "
            + "a type a person confirmed is left alone, doubtful ones go to review")
    public ResponseEntity<java.util.Map<String, Object>> resort() {
        return ResponseEntity.ok(resorter.resort(de.buerokratiecopilot.shared.context.RequestContext.tenantId().orElseThrow()));
    }

    @PostMapping("/{id}/reprocess")
    @PreAuthorize("hasAuthority('documents.review')")
    @Operation(summary = "Retry the failed processing stages of a document and let it continue")
    public ResponseEntity<DocumentResponse> reprocess(@PathVariable UUID id) {
        documentService.require(id);
        for (var step : pipeline.history(id)) {
            if (step.getStatus() == de.buerokratiecopilot.documents.domain.DocumentProcessingStep.Status.FAILED) {
                pipeline.retryStep(id, step.getStep());
            }
        }
        pipeline.advance(id);
        Document after = documentService.require(id);
        return ResponseEntity.ok(DocumentResponse.from(after, documentTypeOf(after), expenseCategoryOf(after)));
    }
}
