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

    public DocumentController(DocumentService documentService,
                              de.buerokratiecopilot.documents.pipeline.DocumentProcessingPipeline pipeline) {
        this.documentService = documentService;
        this.pipeline = pipeline;
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
            Instant updatedAt) {

        static DocumentResponse from(Document document) {
            return new DocumentResponse(document.getId(), document.getOriginalFilename(),
                    document.getMimeType(), document.getSizeBytes(), document.getContentHash(),
                    document.getCurrentVersion(), document.getProcessingStatus().name(),
                    document.getReviewStatus().name(), document.getConfidentiality().name(),
                    document.getSource().name(), document.getDocumentNumber(),
                    document.getDocumentDate(), document.getDocumentTypeId(),
                    document.getDuplicateOfDocumentId(), document.isLegalHold(),
                    document.getReceivedAt(), document.getUpdatedAt());
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
                DocumentResponse.from(received.document()), received.duplicate(),
                received.duplicateOf(), nextStep));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('documents.view')")
    @Operation(summary = "List documents with search, status (repeatable) and source (repeatable) filters")
    public ResponseEntity<Page<DocumentResponse>> list(
            @RequestParam(required = false) @Size(max = 200) String q,
            @RequestParam(required = false) java.util.List<Document.ProcessingStatus> status,
            @RequestParam(required = false) java.util.List<Document.Source> source,
            @PageableDefault(size = 25, sort = "receivedAt") Pageable pageable) {

        Page<DocumentResponse> page = documentService.list(q, status, source, pageable)
                .map(DocumentResponse::from);
        return ResponseEntity.ok(page);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('documents.view')")
    @Operation(summary = "Read one document; an identifier of another tenant is answered with 404")
    public ResponseEntity<DocumentResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(DocumentResponse.from(documentService.require(id)));
    }

    @GetMapping("/{id}/content")
    @PreAuthorize("hasAuthority('documents.view')")
    @Operation(summary = "Download the stored file of the current version")
    public ResponseEntity<InputStreamResource> content(@PathVariable UUID id) throws IOException {
        DocumentService.Download download = documentService.openContent(id);
        InputStream content = download.content();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.contentType()))
                .contentLength(download.sizeBytes())
                // The file name is sanitised before it reaches this header, so a crafted name cannot
                // inject a second header (spec §21.2).
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + download.filename().replace("\"", "") + "\"")
                .body(new InputStreamResource(content));
    }

    @PostMapping("/{id}/status")
    @PreAuthorize("hasAuthority('documents.review')")
    @Operation(summary = "Move a document along the processing pipeline")
    public ResponseEntity<DocumentResponse> changeStatus(@PathVariable UUID id,
                                                         @RequestParam Document.ProcessingStatus status,
                                                         @RequestParam(required = false) String reason) {
        return ResponseEntity.ok(DocumentResponse.from(documentService.changeStatus(id, status, reason)));
    }


    /**
     * Runs the stages that failed again, without repeating those that succeeded, and lets the document
     * continue. A stage that did not fail is left alone: repeating a finished OCR run would only spend time
     * to reach the same text.
     */
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
        return ResponseEntity.ok(DocumentResponse.from(documentService.require(id)));
    }
}
