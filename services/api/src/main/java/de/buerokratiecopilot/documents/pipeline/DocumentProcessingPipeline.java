package de.buerokratiecopilot.documents.pipeline;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.audit.AuditService;
import de.buerokratiecopilot.audit.domain.AuditEvent;
import de.buerokratiecopilot.documents.DocumentBlobRepository;
import de.buerokratiecopilot.documents.DocumentProcessingStepRepository;
import de.buerokratiecopilot.documents.DocumentRepository;
import de.buerokratiecopilot.documents.DocumentVersionRepository;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.documents.domain.DocumentBlob;
import de.buerokratiecopilot.documents.domain.DocumentProcessingStep;
import de.buerokratiecopilot.documents.domain.DocumentStatusMachine;
import de.buerokratiecopilot.documents.domain.DocumentVersion;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.outbox.DomainEvent;
import de.buerokratiecopilot.shared.outbox.OutboxWriter;

/**
 * Runs the document processing pipeline of spec §9.1.
 *
 * <p>Design decisions:</p>
 * <ul>
 *   <li><strong>Every step is recorded.</strong> A step that ran, failed or was deliberately skipped is
 *       stored with its attempt, duration and diagnostics, so "why is this document stuck?" is a query
 *       and not an investigation.</li>
 *   <li><strong>A missing handler is not a simulation.</strong> If no handler is registered for the next
 *       step, the pipeline stops and reports where and why. It never marks an unimplemented step as
 *       successful, which would make an unprocessed document look processed.</li>
 *   <li><strong>Retryable per step.</strong> A failed step can be reset and re-run without repeating the
 *       whole pipeline (spec §9.1: each stage has a mechanism for repeated execution).</li>
 *   <li><strong>Idempotent.</strong> Running the pipeline twice on a finished document changes nothing,
 *       which is what makes it safe to call from a background worker after a restart.</li>
 * </ul>
 */
@Service
public class DocumentProcessingPipeline {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessingPipeline.class);

    /** Order of execution; the same order as in the specification. */
    private static final List<DocumentProcessingStep.Step> ORDER = List.of(
            DocumentProcessingStep.Step.SECURITY_SCAN,
            DocumentProcessingStep.Step.HASH,
            DocumentProcessingStep.Step.DUPLICATE_CHECK,
            DocumentProcessingStep.Step.FORMAT_DETECT,
            DocumentProcessingStep.Step.TEXT_EXTRACT,
            DocumentProcessingStep.Step.OCR,
            DocumentProcessingStep.Step.LANGUAGE_DETECT,
            DocumentProcessingStep.Step.CLASSIFY,
            DocumentProcessingStep.Step.FIELD_EXTRACT,
            DocumentProcessingStep.Step.MATCHING);

    private final DocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final DocumentBlobRepository blobs;
    private final DocumentProcessingStepRepository steps;
    private final AuditService auditService;
    private final OutboxWriter outbox;
    private final Map<DocumentProcessingStep.Step, ProcessingStepHandler> handlers =
            new EnumMap<>(DocumentProcessingStep.Step.class);

    public DocumentProcessingPipeline(DocumentRepository documents, DocumentVersionRepository versions,
                                      DocumentBlobRepository blobs, DocumentProcessingStepRepository steps,
                                      AuditService auditService, OutboxWriter outbox,
                                      List<ProcessingStepHandler> stepHandlers) {
        this.documents = documents;
        this.versions = versions;
        this.blobs = blobs;
        this.steps = steps;
        this.auditService = auditService;
        this.outbox = outbox;
        for (ProcessingStepHandler handler : stepHandlers) {
            ProcessingStepHandler previous = handlers.put(handler.step(), handler);
            if (previous != null) {
                throw new IllegalStateException("Two handlers registered for step " + handler.step());
            }
        }
    }

    /** Result of one advance: how far the pipeline got and where it stopped. */
    public record AdvanceResult(UUID documentId, DocumentProcessingStep.Step stoppedAt,
                                String stopReason, Document.ProcessingStatus status,
                                List<DocumentProcessingStep.Step> executed) {

        public boolean progressed() {
            return !executed.isEmpty();
        }
    }

    /**
     * Advances a document as far as the registered handlers allow.
     *
     * <p>Stops at the first step that has no handler, fails, or is skipped, and returns the reason. The
     * document status always reflects the last stage that really completed.</p>
     */
    @Transactional
    public AdvanceResult advance(UUID documentId) {
        Document document = documents.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown document " + documentId));
        DocumentVersion version = versions.findFirstByDocumentIdOrderByVersionDesc(documentId).orElse(null);
        DocumentBlob blob = version == null ? null : blobs.findById(version.getBlobId()).orElse(null);

        List<DocumentProcessingStep.Step> executed = new java.util.ArrayList<>();

        for (DocumentProcessingStep.Step step : ORDER) {
            DocumentProcessingStep row = stepRow(documentId, version, step);
            if (row.isFinished()) {
                continue;
            }

            ProcessingStepHandler handler = handlers.get(step);
            if (handler == null) {
                String reason = "No handler is registered for this step yet"
                        + (handlerNote(step).isEmpty() ? "" : " (" + handlerNote(step) + ")");
                // Left PENDING on purpose: the step is waiting for its implementation, not skipped.
                return new AdvanceResult(documentId, step, reason, document.getProcessingStatus(), executed);
            }
            if (version == null || blob == null) {
                return new AdvanceResult(documentId, step, "The stored version is missing",
                        document.getProcessingStatus(), executed);
            }

            row.start();
            steps.save(row);

            ProcessingStepHandler.StepResult result;
            try {
                result = handler.handle(new ProcessingStepHandler.ProcessingContext(
                        document.getTenantId(), document, version, blob));
            }
            catch (RuntimeException ex) {
                log.warn("Pipeline step {} failed for document {}", step, documentId, ex);
                row.fail("STEP_EXCEPTION", rootMessage(ex));
                steps.save(row);
                auditFailure(document, step, rootMessage(ex));
                return new AdvanceResult(documentId, step, rootMessage(ex), document.getProcessingStatus(),
                        executed);
            }

            if (result.isFailed()) {
                row.fail("STEP_FAILED", result.reason());
                steps.save(row);
                auditFailure(document, step, result.reason());
                return new AdvanceResult(documentId, step, result.reason(), document.getProcessingStatus(),
                        executed);
            }

            if (result.isSkipped()) {
                row.skip(result.reason());
            }
            else {
                row.succeed(result.diagnostics());
            }
            steps.save(row);
            executed.add(step);

            if (result.nextStatus() != null && result.nextStatus() != document.getProcessingStatus()) {
                DocumentStatusMachine.requireTransition(document.getProcessingStatus(), result.nextStatus());
                document.setProcessingStatus(result.nextStatus());
                documents.save(document);
            }

            if (result.isSkipped()) {
                // A skipped step is a decision, not a failure: the pipeline stops here so the reason
                // stays attached to the exact stage that produced it.
                return new AdvanceResult(documentId, step, result.reason(), document.getProcessingStatus(),
                        executed);
            }
        }

        outbox.publish(new DomainEvent("document.processing_completed", "documents", documentId,
                document.getTenantId(), Map.of("status", document.getProcessingStatus().name())));

        return new AdvanceResult(documentId, null, null, document.getProcessingStatus(), executed);
    }

    /**
     * Resets a failed step so it can run again, without repeating the stages that already succeeded.
     *
     * @throws IllegalStateException when the step is not in a failed state
     */
    @Transactional
    public DocumentProcessingStep retryStep(UUID documentId, DocumentProcessingStep.Step step) {
        DocumentProcessingStep row = steps.findByDocumentIdAndStep(documentId, step)
                .orElseThrow(() -> new IllegalArgumentException("The document has no such step: " + step));
        if (row.getStatus() != DocumentProcessingStep.Status.FAILED) {
            throw new IllegalStateException("Only a failed step can be retried; " + step + " is "
                    + row.getStatus());
        }
        row.resetForRetry();
        steps.save(row);

        auditService.record(AuditService.AuditEntry
                .builder(AuditEvent.Category.DOCUMENT, "PROCESSING_STEP_RETRIED")
                .tenant(RequestContext.tenantId().orElse(null))
                .entity("documents", documentId, null)
                .description("Step " + step + " was reset for another attempt")
                .build());
        return row;
    }

    /** Current pipeline state of a document, for the document card and the review centre. */
    @Transactional(readOnly = true)
    public List<DocumentProcessingStep> history(UUID documentId) {
        return steps.findByDocumentIdOrderByCreatedAtAsc(documentId);
    }

    private String handlerNote(DocumentProcessingStep.Step step) {
        return Optional.ofNullable(handlers.get(step))
                .map(ProcessingStepHandler::pendingImplementationNote)
                .orElse("");
    }

    private DocumentProcessingStep stepRow(UUID documentId, DocumentVersion version,
                                           DocumentProcessingStep.Step step) {
        return steps.findByDocumentIdAndStep(documentId, step)
                .orElseGet(() -> steps.save(new DocumentProcessingStep(UUID.randomUUID(), documentId,
                        version == null ? null : version.getId(), step)));
    }

    private void auditFailure(Document document, DocumentProcessingStep.Step step, String reason) {
        auditService.record(AuditService.AuditEntry
                .builder(AuditEvent.Category.DOCUMENT, "PROCESSING_STEP_FAILED")
                .tenant(document.getTenantId())
                .entity("documents", document.getId(), document.getOriginalFilename())
                .description("Step " + step + " failed: " + reason)
                .severity(AuditEvent.Severity.WARNING)
                .result(AuditEvent.Result.FAILURE)
                .build());
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    /** Records a step that already happened outside the pipeline (upload performs three of them). */
    @Transactional
    public void recordCompletedStep(UUID documentId, UUID versionId, DocumentProcessingStep.Step step,
                                    Map<String, Object> diagnostics) {
        DocumentProcessingStep row = steps.findByDocumentIdAndStep(documentId, step)
                .orElseGet(() -> new DocumentProcessingStep(UUID.randomUUID(), documentId, versionId, step));
        row.start();
        row.succeed(diagnostics);
        steps.save(row);
    }
}
