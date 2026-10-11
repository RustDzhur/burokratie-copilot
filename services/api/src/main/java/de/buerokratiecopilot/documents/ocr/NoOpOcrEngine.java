package de.buerokratiecopilot.documents.ocr;

import java.util.List;

/**
 * The engine used when OCR is not configured.
 *
 * <p>It answers that it is unavailable and refuses to recognise, which makes the pipeline record the
 * pages as "not examined". Returning empty text instead would make a scanned document look like a
 * blank one.</p>
 */
public class NoOpOcrEngine implements OcrEngine {

    @Override
    public String name() {
        return "none";
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public List<String> availableLanguages() {
        return List.of();
    }

    @Override
    public Result recognise(byte[] image, List<String> languages) {
        return Result.failure(name(), String.join("+", languages),
                "No OCR engine is configured in this deployment");
    }
}
