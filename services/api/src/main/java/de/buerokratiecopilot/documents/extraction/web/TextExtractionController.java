package de.buerokratiecopilot.documents.extraction.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.documents.domain.DocumentPage;
import de.buerokratiecopilot.documents.extraction.TextExtractionService;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * The text read out of a document, page by page (spec §13; B-006).
 *
 * <p>{@code TextExtractionService} was implemented and tested with no endpoint, so the pages it produces —
 * the ground every extracted value is checked against — could not be read by anything.</p>
 *
 * <p><strong>Every page says where its text came from.</strong> A page read from the text layer of a PDF and
 * a page produced by OCR are not the same evidence: OCR is wrong in ways a text layer is not, and a person
 * confirming a figure needs to know which of the two they are looking at. The answer carries
 * {@code ocrUsed}, the engine and the language, so that "the letter says 119,00" is a statement about the
 * paper rather than about a recognition step.</p>
 *
 * <p><strong>A page with no text is served as an empty page, not omitted.</strong> "This page was read and
 * nothing was found on it" and "this page was not read" are different statements, and a list that quietly
 * dropped the empty ones would leave a person unable to tell them apart — which is the difference between a
 * blank form and a page nobody looked at.</p>
 *
 * <p><strong>Nothing here writes.</strong> These are read routes; extraction is part of the pipeline and
 * happens on upload, and a read route that could trigger it would let somebody reprocess a document by
 * refreshing a page.</p>
 */
@RestController
@RequestMapping("/api/v1/documents")
@Tag(name = "Text extraction", description = "The text read out of a document, page by page")
public class TextExtractionController {

    private static final String SEE = "hasAnyAuthority('documents.view','documents.review')";

    private final TextExtractionService extraction;

    public TextExtractionController(TextExtractionService extraction) {
        this.extraction = extraction;
    }

    /**
     * The formats the platform can read.
     *
     * <p>Served rather than documented elsewhere, because a list in a manual and a list in the code drift
     * apart and the one a customer reads is the manual.</p>
     */
    @GetMapping("/extraction/supported-formats")
    @PreAuthorize(SEE)
    @Operation(summary = "The formats the platform can read")
    public Map<String, Object> supportedFormats() {
        List<String> formats = extraction.supportedFormats();
        return Map.of("formats", formats,
                "note", "Formate außerhalb dieser Liste werden nicht gelesen; für gescannte Seiten wird "
                        + "die Texterkennung verwendet, wo sie eingerichtet ist.");
    }

    /**
     * The pages of a document, each with where its text came from.
     *
     * <p>An empty page is served, because "read and found nothing" and "not read" are different statements
     * and a list that dropped the first would leave a person unable to tell them apart.</p>
     */
    @GetMapping("/{documentId}/pages")
    @PreAuthorize(SEE)
    @Operation(summary = "The pages of a document, each saying whether its text came from OCR")
    public Map<String, Object> pages(@PathVariable UUID documentId) {
        RequestContext.tenantId().orElseThrow();
        List<DocumentPage> pages = extraction.pagesOf(documentId);

        List<Map<String, Object>> rows = pages.stream().map(page -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("pageId", page.getId() == null ? null : page.getId().toString());
            row.put("pageNumber", page.getPageNumber());
            row.put("text", page.getTextContent());
            // Whether the text was read or recognised, which is the difference between the letter and a
            // machine's reading of it.
            row.put("ocrUsed", page.isOcrUsed());
            row.put("ocrEngine", page.getOcrEngine());
            row.put("ocrLanguage", page.getOcrLanguage());
            row.put("empty", page.getTextContent() == null || page.getTextContent().isBlank());
            return row;
        }).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("documentId", documentId.toString());
        result.put("pages", rows);
        result.put("pageCount", rows.size());
        // Counted rather than left to the caller: how much of a document was recognised rather than read is
        // the thing that decides how much of it needs a person.
        result.put("ocrPageCount", rows.stream().filter(row -> Boolean.TRUE.equals(row.get("ocrUsed")))
                .count());
        result.put("emptyPageCount", rows.stream().filter(row -> Boolean.TRUE.equals(row.get("empty")))
                .count());
        return result;
    }
}
