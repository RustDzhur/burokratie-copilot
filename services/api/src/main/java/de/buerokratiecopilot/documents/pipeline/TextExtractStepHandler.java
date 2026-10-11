package de.buerokratiecopilot.documents.pipeline;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.documents.domain.DocumentProcessingStep;
import de.buerokratiecopilot.documents.extraction.ExtractedText;
import de.buerokratiecopilot.documents.extraction.TextExtractionService;

/**
 * Extracts the text of a document (spec §9.1 step 5).
 *
 * <p>Three outcomes, each mapped to a different follow-up:</p>
 * <ul>
 *   <li><strong>text found</strong> — the document moves to {@code EXTRACTING}, and classification
 *       is the next stage;</li>
 *   <li><strong>no text layer at all</strong> — the document moves to {@code OCR_PENDING}: a purely
 *       scanned page is not a failure, it is a different route through the pipeline;</li>
 *   <li><strong>format without an extractor</strong> — the step is reported as skipped with the
 *       reason and the document goes to {@code REVIEW_REQUIRED}. Nothing pretends the text is
 *       available, and a human decides what to do with the file.</li>
 * </ul>
 *
 * <p>A page that has text and a page that does not can occur in the same document (a scanned annex
 * inside a digital letter). That case is recorded in the diagnostics rather than hidden: the page
 * count of empty pages travels with the result, and the OCR stage can pick them up individually.</p>
 */
@Component
public class TextExtractStepHandler implements ProcessingStepHandler {

    private final TextExtractionService extraction;

    public TextExtractStepHandler(TextExtractionService extraction) {
        this.extraction = extraction;
    }

    @Override
    public DocumentProcessingStep.Step step() {
        return DocumentProcessingStep.Step.TEXT_EXTRACT;
    }

    @Override
    public StepResult handle(ProcessingContext context) {
        TextExtractionService.Result result = extraction.extract(context.document(), context.version(),
                context.blob());

        if (!result.supported()) {
            return StepResult.skipped(result.reason(), Document.ProcessingStatus.REVIEW_REQUIRED);
        }

        ExtractedText text = result.text();
        Map<String, Object> diagnostics = new LinkedHashMap<>(
                extraction.diagnosticsOf(text, result.format()));
        diagnostics.put("supportedFormats", extraction.supportedFormats());

        if (text.needsOcr()) {
            return StepResult.succeeded(diagnostics, Document.ProcessingStatus.OCR_PENDING);
        }
        return StepResult.succeeded(diagnostics, Document.ProcessingStatus.EXTRACTING);
    }
}
