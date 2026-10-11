package de.buerokratiecopilot.documents.pipeline;

import org.springframework.stereotype.Component;

import de.buerokratiecopilot.documents.classification.ClassificationService;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.documents.domain.DocumentProcessingStep;

/**
 * Classifies a document and applies the confidence thresholds (spec §9.1 step 8, §9.3).
 *
 * <p>Status convention used by this pipeline: a status names the stage that has been <em>completed</em>.
 * The classification step therefore leaves the document in {@code CLASSIFYING} on success —
 * classification is finished, matching is next — and in {@code REVIEW_REQUIRED} when a human has to
 * confirm the type first.</p>
 *
 * <p>When the classifier cannot decide, the step does not fail: "we do not know what this is" is a
 * result, and it routes the document to a person with the reason attached. A failure is reserved for
 * the case where the stage could not run at all.</p>
 */
@Component
public class ClassifyStepHandler implements ProcessingStepHandler {

    private final ClassificationService classification;

    public ClassifyStepHandler(ClassificationService classification) {
        this.classification = classification;
    }

    @Override
    public DocumentProcessingStep.Step step() {
        return DocumentProcessingStep.Step.CLASSIFY;
    }

    @Override
    public StepResult handle(ProcessingContext context) {
        ClassificationService.Outcome outcome = classification.classify(context.document(),
                context.version(), context.document().getSender());

        if (outcome.requiresReview()) {
            return StepResult.succeeded(outcome.diagnostics(), Document.ProcessingStatus.REVIEW_REQUIRED);
        }
        return StepResult.succeeded(outcome.diagnostics(), Document.ProcessingStatus.CLASSIFYING);
    }
}
