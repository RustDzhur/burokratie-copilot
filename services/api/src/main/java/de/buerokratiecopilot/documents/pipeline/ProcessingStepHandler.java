package de.buerokratiecopilot.documents.pipeline;

import java.util.Map;
import java.util.UUID;

import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.documents.domain.DocumentBlob;
import de.buerokratiecopilot.documents.domain.DocumentProcessingStep;
import de.buerokratiecopilot.documents.domain.DocumentVersion;

/**
 * Executes one step of the document processing pipeline (spec §9.1).
 *
 * <p>A handler is a small, replaceable unit: text extraction, OCR, classification and matching are
 * implemented by their own handlers as those tasks are built, and the pipeline itself does not change.
 * A step without a registered handler is reported as pending with the reason, never simulated.</p>
 */
public interface ProcessingStepHandler {

    DocumentProcessingStep.Step step();

    /**
     * Name of the task that will implement this step, shown to the operator while it is missing.
     * Empty for handlers that are implemented.
     */
    default String pendingImplementationNote() {
        return "";
    }

    StepResult handle(ProcessingContext context);

    /** Everything a step may need; passed explicitly so a handler has no hidden dependencies. */
    record ProcessingContext(UUID tenantId, Document document, DocumentVersion version, DocumentBlob blob) {
    }

    /**
     * Outcome of a step.
     *
     * @param nextStatus status the document moves to when the step succeeded, or {@code null} to keep
     *                   the current one
     */
    record StepResult(DocumentProcessingStep.Status status, String reason,
                      Map<String, Object> diagnostics, Document.ProcessingStatus nextStatus) {

        public static StepResult succeeded(Map<String, Object> diagnostics,
                                           Document.ProcessingStatus nextStatus) {
            return new StepResult(DocumentProcessingStep.Status.SUCCEEDED, null, diagnostics, nextStatus);
        }

        public static StepResult skipped(String reason, Document.ProcessingStatus nextStatus) {
            return new StepResult(DocumentProcessingStep.Status.SKIPPED, reason, Map.of(), nextStatus);
        }

        public static StepResult failed(String reason) {
            return new StepResult(DocumentProcessingStep.Status.FAILED, reason, Map.of(), null);
        }

        public boolean isFailed() {
            return status == DocumentProcessingStep.Status.FAILED;
        }

        public boolean isSkipped() {
            return status == DocumentProcessingStep.Status.SKIPPED;
        }
    }
}
