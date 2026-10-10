package de.buerokratiecopilot.documents.pipeline;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.documents.domain.DocumentProcessingStep;

/**
 * Determines the file format of the stored document (spec §9.1 step 4).
 *
 * <p>The format itself is already established by the security check, which reads the magic bytes
 * before a document is accepted. This step records that finding in the pipeline, verifies that the
 * stored object is readable and consistent with the metadata, and publishes the format so the
 * extraction stage knows which parser to use.</p>
 *
 * <p>The document moves to {@code QUARANTINED} on success, which in this platform means "entry checks
 * passed, queued for content processing" — the security decision was already made at upload.</p>
 */
@Component
public class FormatDetectStepHandler implements ProcessingStepHandler {

    @Override
    public DocumentProcessingStep.Step step() {
        return DocumentProcessingStep.Step.FORMAT_DETECT;
    }

    @Override
    public StepResult handle(ProcessingContext context) {
        Document document = context.document();
        Map<String, Object> stored = context.blob().getScanDetails();

        String detected = stored == null ? null : String.valueOf(stored.get("detectedContentType"));
        if (detected == null || detected.isBlank() || "null".equals(detected)) {
            return StepResult.failed("The stored file has no recorded content type; "
                    + "the security check did not run for this document");
        }
        if (!document.getMimeType().equals(detected) && !isCompatible(document.getMimeType(), detected)) {
            return StepResult.failed("The recorded content type (%s) contradicts the document metadata (%s)"
                    .formatted(detected, document.getMimeType()));
        }
        if (!context.blob().isProcessable()) {
            return StepResult.failed("The stored file is not released for processing (scan status "
                    + context.blob().getScanStatus() + ")");
        }

        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("contentType", detected);
        diagnostics.put("sizeBytes", context.blob().getSizeBytes());
        diagnostics.put("contentHash", context.blob().getContentHash());
        diagnostics.put("parser", parserFor(detected));

        return StepResult.succeeded(diagnostics, Document.ProcessingStatus.QUARANTINED);
    }

    /** Which parser the extraction stage will have to use; recorded so the next stage is explicit. */
    static String parserFor(String contentType) {
        return switch (contentType) {
            case "application/pdf" -> "pdfbox";
            case "image/png", "image/jpeg", "image/tiff" -> "ocr-required";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "poi-docx";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "poi-xlsx";
            case "text/plain", "text/csv" -> "plain-text";
            case "message/rfc822" -> "jakarta-mail";
            case "application/xml", "text/xml" -> "xml";
            default -> "unsupported";
        };
    }

    private static boolean isCompatible(String declared, String detected) {
        if (detected.equals("text/plain") && (declared.startsWith("text/") || declared.equals("application/xml"))) {
            return true;
        }
        return detected.equals("application/xml") && declared.equals("text/xml");
    }
}
