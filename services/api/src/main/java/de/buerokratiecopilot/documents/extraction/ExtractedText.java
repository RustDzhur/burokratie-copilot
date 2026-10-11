package de.buerokratiecopilot.documents.extraction;

import java.util.List;
import java.util.Map;

/**
 * Text extracted from a document, page by page (spec §9.1 step 5, §9.2).
 *
 * <p>Page granularity is not decoration: the specification requires every extracted value to name
 * the page and snippet it came from, and a reviewer must be able to jump to the exact place. A
 * single blob of text would make both impossible.</p>
 *
 * @param pages       pages or sections in document order, never empty on success
 * @param method      how the text was obtained, recorded with the document for traceability
 * @param needsOcr    true when pages carry no text layer at all, so OCR is the next step
 * @param diagnostics free-form details, for example the number of sheets or table rows
 */
public record ExtractedText(List<Page> pages, Method method, boolean needsOcr,
                            Map<String, Object> diagnostics) {

    /** Where the text came from; the distinction matters when the quality is questioned later. */
    public enum Method {
        /** A digital text layer inside the file (PDF text layer, DOCX, XLSX, …). */
        TEXT_LAYER,
        /** Plain text by definition. */
        PLAIN_TEXT,
        /** Text produced by an OCR engine (task B-007). */
        OCR,
        /** Structured data (XML, e-invoice) instead of a rendered page. */
        STRUCTURED
    }

    /**
     * One page of the source document.
     *
     * @param number    1-based page number as it appears in the document
     * @param text      extracted text; may be empty when the page is a pure image
     * @param confidence engine confidence when the source provides one, otherwise {@code null}
     */
    public record Page(int number, String text, Double confidence) {

        public boolean isEmpty() {
            return text == null || text.isBlank();
        }
    }

    public ExtractedText {
        pages = pages == null ? List.of() : List.copyOf(pages);
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
    }

    public String fullText() {
        StringBuilder builder = new StringBuilder();
        for (Page page : pages) {
            if (page.isEmpty()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append("\n\n");
            }
            builder.append(page.text().strip());
        }
        return builder.toString();
    }

    public int totalCharacters() {
        return pages.stream().mapToInt(page -> page.text() == null ? 0 : page.text().length()).sum();
    }

    public int emptyPageCount() {
        return (int) pages.stream().filter(Page::isEmpty).count();
    }

    public static ExtractedText of(List<Page> pages, Method method, Map<String, Object> diagnostics) {
        boolean needsOcr = !pages.isEmpty() && pages.stream().allMatch(Page::isEmpty);
        return new ExtractedText(pages, method, needsOcr, diagnostics);
    }

    /** A document whose format carries no page concept (DOCX, CSV) is reported as one section. */
    public static ExtractedText singleSection(String text, Method method, Map<String, Object> diagnostics) {
        return new ExtractedText(List.of(new Page(1, text, null)), method, text == null || text.isBlank(),
                diagnostics);
    }

    public static ExtractedText empty(Method method, String reason) {
        return new ExtractedText(List.of(), method, true, Map.of("reason", reason));
    }
}
