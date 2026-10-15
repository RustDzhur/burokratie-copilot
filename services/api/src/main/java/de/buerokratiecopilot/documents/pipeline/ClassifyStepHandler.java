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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ClassifyStepHandler.class);

    private final ClassificationService classification;
    private final de.buerokratiecopilot.documents.fields.FieldExtractionService extraction;

    private final de.buerokratiecopilot.documents.sender.SenderService senders;

    public ClassifyStepHandler(ClassificationService classification,
                               de.buerokratiecopilot.documents.fields.FieldExtractionService extraction,
                               de.buerokratiecopilot.documents.sender.SenderService senders) {
        this.classification = classification;
        this.extraction = extraction;
        this.senders = senders;
    }

    @Override
    public DocumentProcessingStep.Step step() {
        return DocumentProcessingStep.Step.CLASSIFY;
    }

    @Override
    public StepResult handle(ProcessingContext context) {
        // Who sent the letter is read as soon as there is text, whether or not the type needs a person's check: the sender is
        // there to be found later, and a document that waits for a person has a sender too.
        senders.apply(context.document());
        if (context.document().getDocumentTypeId() != null) {
            // The stage is the only one that sets a type by itself, so a type present before it runs was chosen by a person.
            // The rules do not ask again about what a person has decided.
            return StepResult.succeeded(java.util.Map.of("typeChosenByPerson", true),
                    Document.ProcessingStatus.CLASSIFYING);
        }
        ClassificationService.Outcome outcome = classification.classify(context.document(),
                context.version(), context.document().getSender());

        if (outcome.requiresReview()) {
            // The pipeline stops here until a person decides, so the extraction stage will not run before
            // that. A reviewer who is shown an empty table cannot judge anything, and a document that waits
            // with nothing read from it looks as if it had not been looked at. The values are read now,
            // against the suggested type or the generic field set, and stay unverified until confirmed.
            java.util.Map<String, Object> diagnostics = new java.util.LinkedHashMap<>(outcome.diagnostics());
            try {
                var preview = extraction.extract(context.document(), context.version());
                diagnostics.put("previewFields", preview.found().size());
            }
            catch (RuntimeException ex) {
                log.warn("Reading the fields for the review of document {} failed: {}",
                        context.document().getId(), ex.getMessage());
            }
            return StepResult.succeeded(diagnostics, Document.ProcessingStatus.REVIEW_REQUIRED);
        }
        return StepResult.succeeded(outcome.diagnostics(), Document.ProcessingStatus.CLASSIFYING);
    }
}
