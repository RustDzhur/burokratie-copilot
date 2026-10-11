package de.buerokratiecopilot.documents.pipeline;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.documents.domain.DocumentProcessingStep;
import de.buerokratiecopilot.documents.fields.FieldExtractionService;

/**
 * Extracts the declared fields of a document type (spec §9.1 step 9, §9.4).
 *
 * <p>The stage can succeed with nothing found, and that is a meaningful outcome: the document type is
 * known, the fields are known, and the values are simply not in the text. The step then reports which
 * fields are missing so the following review stage knows what to ask a human for. It only fails when
 * it could not run at all.</p>
 *
 * <p>Missing required or critical fields do not fail the step either — they are a property of the
 * document, not of the run, and routing the document to review is the review centre's job (task
 * B-011), which reads the same definitions.</p>
 */
@Component
public class FieldExtractStepHandler implements ProcessingStepHandler {

    private final FieldExtractionService extraction;

    public FieldExtractStepHandler(FieldExtractionService extraction) {
        this.extraction = extraction;
    }

    @Override
    public DocumentProcessingStep.Step step() {
        return DocumentProcessingStep.Step.FIELD_EXTRACT;
    }

    @Override
    public StepResult handle(ProcessingContext context) {
        if (context.document().getDocumentTypeId() == null) {
            return StepResult.skipped(
                    "The document has no type yet, so there is nothing to extract: field definitions "
                            + "belong to a document type",
                    Document.ProcessingStatus.REVIEW_REQUIRED);
        }

        FieldExtractionService.Result result = extraction.extract(context.document(), context.version());

        Map<String, Object> diagnostics = new LinkedHashMap<>(result.diagnostics());
        diagnostics.put("declaredFields", result.found().size() + result.notFound().size()
                + result.withoutHint().size());

        return StepResult.succeeded(diagnostics, Document.ProcessingStatus.CLASSIFYING);
    }
}
