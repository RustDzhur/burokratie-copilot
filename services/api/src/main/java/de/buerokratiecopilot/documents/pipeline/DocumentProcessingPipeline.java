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
    private final de.buerokratiecopilot.documents.review.ReviewService reviews;
    private final Map<DocumentProcessingStep.Step, ProcessingStepHandler> handlers =
            new EnumMap<>(DocumentProcessingStep.Step.class);
    /** One transaction per unit of work; see {@link #advance}. */
    private final org.springframework.transaction.support.TransactionTemplate tx;

    public DocumentProcessingPipeline(DocumentRepository documents, DocumentVersionRepository versions,
                                      DocumentBlobRepository blobs, DocumentProcessingStepRepository steps,
                                      AuditService auditService, OutboxWriter outbox,
                                      de.buerokratiecopilot.documents.review.ReviewService reviews,
                                      List<ProcessingStepHandler> stepHandlers,
                                      org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.documents = documents;
        this.versions = versions;
        this.blobs = blobs;
        this.steps = steps;
        this.auditService = auditService;
        this.outbox = outbox;
        this.reviews = reviews;
        this.tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        // REQUIRES_NEW, not REQUIRED: the pipeline is usually started from inside the outbox delivery, which
        // runs in a transaction of its own. Joining it would put every step back into one transaction, and a
        // step that fails would again poison the record of its own failure. Everything the steps read has
        // been committed by the upload that announced the document.
        this.tx.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
    public AdvanceResult advance(UUID documentId) {
        // Every step runs in a transaction of its own. A step that throws (an unreadable PDF, say) passes its
        // exception through transactional services, and each of them marks the surrounding transaction
        // rollback-only. In one big transaction the failure could be caught but never saved: the document
        // stayed "received" for ever and nobody was told why. Here the failed step rolls back alone, what
        // earlier steps produced stays committed, and the failure is recorded in a fresh transaction.
        Document.ProcessingStatus current = tx.execute(status -> documents.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown document " + documentId))
                .getProcessingStatus());
        if (current == Document.ProcessingStatus.DUPLICATE) {
            // A duplicate is kept but never processed: "process again" must leave it as it is, not walk on and fail
            // trying to move it to another status.
            return new AdvanceResult(documentId, DocumentProcessingStep.Step.DUPLICATE_CHECK,
                    "The document is a duplicate and is not processed again", current, List.of());
        }

        List<DocumentProcessingStep.Step> executed = new java.util.ArrayList<>();
        for (DocumentProcessingStep.Step step : ORDER) {
            StepOutcome outcome;
            try {
                outcome = tx.execute(status -> runStep(documentId, step));
            }
            catch (RuntimeException ex) {
                log.warn("Pipeline step {} failed for document {}", step, documentId, ex);
                return tx.execute(status -> recordCrash(documentId, step, ex, executed));
            }
            if (outcome.ran()) {
                executed.add(step);
            }
            if (outcome.stoppedAt() != null) {
                return new AdvanceResult(documentId, outcome.stoppedAt(), outcome.reason(), outcome.status(),
                        executed);
            }
        }

        return tx.execute(status -> {
            Document document = documents.findById(documentId).orElseThrow();
            // Every stage is finished. A document that was waiting for people and has no question left is ready: the status
            // only changes when a stage runs, and after the last answer none does, so without this a document filed
            // in the right drawer would stay "in processing" for ever.
            if (document.getProcessingStatus() == Document.ProcessingStatus.REVIEW_REQUIRED
                    && !reviews.hasOpenReview(documentId)) {
                document.setProcessingStatus(Document.ProcessingStatus.READY);
                if (document.getReviewStatus() == Document.ReviewStatus.PENDING) {
                    document.setReviewStatus(Document.ReviewStatus.APPROVED);
                }
                documents.save(document);
            }
            outbox.publish(new DomainEvent("document.processing_completed", "documents", documentId,
                    document.getTenantId(), Map.of("status", document.getProcessingStatus().name())));
            return new AdvanceResult(documentId, null, null, document.getProcessingStatus(), executed);
        });
    }

    /** What one step did: whether it ran, and whether and why the pipeline has to stop after it. */
    private record StepOutcome(boolean ran, DocumentProcessingStep.Step stoppedAt, String reason,
                               Document.ProcessingStatus status) {

        static StepOutcome next(boolean ran) {
            return new StepOutcome(ran, null, null, null);
        }

        static StepOutcome stop(boolean ran, DocumentProcessingStep.Step step, String reason,
                                Document.ProcessingStatus status) {
            return new StepOutcome(ran, step, reason, status);
        }
    }

    private AdvanceResult recordCrash(UUID documentId, DocumentProcessingStep.Step step, RuntimeException ex,
                                      List<DocumentProcessingStep.Step> executed) {
        Document document = documents.findById(documentId).orElseThrow();
        DocumentVersion version = versions.findFirstByDocumentIdOrderByVersionDesc(documentId).orElse(null);
        DocumentProcessingStep row = stepRow(documentId, version, step);
        row.fail("STEP_EXCEPTION", rootMessage(ex));
        steps.save(row);
        auditFailure(document, step, rootMessage(ex));
        return new AdvanceResult(documentId, step, rootMessage(ex), document.getProcessingStatus(), executed);
    }

    private StepOutcome runStep(UUID documentId, DocumentProcessingStep.Step step) {
        Document document = documents.findById(documentId).orElseThrow();
        DocumentVersion version = versions.findFirstByDocumentIdOrderByVersionDesc(documentId).orElse(null);
        DocumentBlob blob = version == null ? null : blobs.findById(version.getBlobId()).orElse(null);

        DocumentProcessingStep row = stepRow(documentId, version, step);
        if (row.isFinished()) {
            return StepOutcome.next(false);
        }

        ProcessingStepHandler handler = handlers.get(step);
        if (handler == null) {
            String reason = "No handler is registered for this step yet"
                    + (handlerNote(step).isEmpty() ? "" : " (" + handlerNote(step) + ")");
            // Left PENDING on purpose: the step is waiting for its implementation, not skipped.
            return StepOutcome.stop(false, step, reason, document.getProcessingStatus());
        }
        if (version == null || blob == null) {
            return StepOutcome.stop(false, step, "The stored version is missing", document.getProcessingStatus());
        }

        row.start();
        steps.save(row);

        ProcessingStepHandler.StepResult result = handler.handle(new ProcessingStepHandler.ProcessingContext(
                document.getTenantId(), document, version, blob));

        if (result.isFailed()) {
            row.fail("STEP_FAILED", result.reason());
            steps.save(row);
            auditFailure(document, step, result.reason());
            return StepOutcome.stop(false, step, result.reason(), document.getProcessingStatus());
        }

        if (result.isSkipped()) {
            row.skip(result.reason());
        }
        else {
            row.succeed(result.diagnostics());
        }
        steps.save(row);

        if (result.nextStatus() != null && result.nextStatus() != document.getProcessingStatus()) {
            DocumentStatusMachine.requireTransition(document.getProcessingStatus(), result.nextStatus());
            document.setProcessingStatus(result.nextStatus());
            if (result.nextStatus() == Document.ProcessingStatus.REVIEW_REQUIRED) {
                // A document waiting for a human must also say so in its review state and appear
                // in the review centre, otherwise it would wait forever without anyone knowing
                // what for. The reason travels with the entry: a queue a person cannot act on is
                // just a backlog.
                document.setReviewStatus(Document.ReviewStatus.PENDING);
                reviews.raise(document, version, reviewTypeFor(step), describe(step, result), null);
            }
            documents.save(document);
        }

        if (result.isSkipped()) {
            // A skipped step is a decision, not a failure: the pipeline stops here so the reason
            // stays attached to the exact stage that produced it.
            return StepOutcome.stop(true, step, result.reason(), document.getProcessingStatus());
        }

        if (document.getProcessingStatus() == Document.ProcessingStatus.REVIEW_REQUIRED
                && reviews.hasOpenReview(documentId)) {
            // A document waiting for a human must not keep moving. Without this the next stage
            // would overwrite the status and later stages would act on data nobody has confirmed
            // - classification that a person still has to accept, for example. The pipeline
            // resumes from here once the review is decided.
            return StepOutcome.stop(true, step,
                    "The document requires human review before processing can continue",
                    document.getProcessingStatus());
        }
        return StepOutcome.next(true);
    }

    /** Which kind of question a stage leaves for a person. */
    private static de.buerokratiecopilot.documents.domain.DocumentReview.ReviewType reviewTypeFor(
            DocumentProcessingStep.Step step) {
        return switch (step) {
            case CLASSIFY -> de.buerokratiecopilot.documents.domain.DocumentReview.ReviewType.CLASSIFICATION;
            case FIELD_EXTRACT, TEXT_EXTRACT, OCR ->
                    de.buerokratiecopilot.documents.domain.DocumentReview.ReviewType.EXTRACTION;
            case MATCHING -> de.buerokratiecopilot.documents.domain.DocumentReview.ReviewType.MATCHING;
            case SECURITY_SCAN -> de.buerokratiecopilot.documents.domain.DocumentReview.ReviewType.SECURITY;
            case DUPLICATE_CHECK -> de.buerokratiecopilot.documents.domain.DocumentReview.ReviewType.DUPLICATE;
            default -> de.buerokratiecopilot.documents.domain.DocumentReview.ReviewType.COMPLETENESS;
        };
    }

    /** The reason shown in the queue: the step's own words, or its rationale. */
    private static String describe(DocumentProcessingStep.Step step,
                                   ProcessingStepHandler.StepResult result) {
        if (result.reason() != null && !result.reason().isBlank()) {
            return result.reason();
        }
        Object rationale = result.diagnostics() == null ? null : result.diagnostics().get("rationale");
        if (rationale != null) {
            return String.valueOf(rationale);
        }
        return "The stage " + step + " needs a human decision";
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
