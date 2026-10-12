package de.buerokratiecopilot.documents.review.web;

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

import de.buerokratiecopilot.documents.classification.ClassificationService;
import de.buerokratiecopilot.documents.domain.DocumentClassification;
import de.buerokratiecopilot.documents.domain.DocumentExtraction;
import de.buerokratiecopilot.documents.fields.FieldExtractionService;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * What the platform decided about a document, and where each value came from (spec §14, §15; B-008, B-009).
 *
 * <p>{@code ClassificationService} and {@code FieldExtractionService} were implemented and tested with no
 * endpoint, so the review centre had nothing to show and a person could not see why the platform had filed a
 * document the way it did.</p>
 *
 * <p><strong>Every extracted value is served with its source, and a value without one says so.</strong> A
 * figure taken from a letter is only usable if the person checking it can see the page and the sentence it
 * came from; one that arrived without a hint is a figure somebody has to take on trust, and the answer
 * distinguishes the two rather than presenting them alike.</p>
 *
 * <p><strong>The history is served, not only the current answer.</strong> A document that was classified
 * twice — once automatically and once by a person — has two entries, and which one counts is decided by
 * {@code isCurrent} rather than by position: a list ordered by time is not the same statement as a list with
 * one current entry, and only the second survives a correction being made later.</p>
 *
 * <p><strong>Nothing here changes anything.</strong> These are read routes: the review centre reads what the
 * platform decided, and the operations that change a decision are separate and named as such in the service.
 * A read route that could confirm a value by accident is one that would do it while somebody was scrolling.</p>
 */
@RestController
@RequestMapping("/api/v1/documents")
@Tag(name = "Document review", description = "Classifications and extracted values, with their sources")
public class DocumentReviewController {

    private static final String SEE = "hasAnyAuthority('documents.view','documents.review')";

    private final ClassificationService classifications;
    private final FieldExtractionService fields;

    public DocumentReviewController(ClassificationService classifications, FieldExtractionService fields) {
        this.classifications = classifications;
        this.fields = fields;
    }

    /**
     * Every classification of a document, newest first, with which one counts.
     *
     * <p>Confidence is served as it is rather than rounded into a word: the number is what a threshold is
     * compared against, and a service that turned 0.71 into "probably" would hide the comparison that
     * decided the review.</p>
     */
    @GetMapping("/{documentId}/classifications")
    @PreAuthorize(SEE)
    @Operation(summary = "Every classification of a document, with which one counts")
    public List<Map<String, Object>> classifications(@PathVariable UUID documentId) {
        RequestContext.tenantId().orElseThrow();
        return classifications.historyOf(documentId).stream().map(entry -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("classificationId", text(entry.getId()));
            row.put("documentTypeId", text(entry.getDocumentTypeId()));
            row.put("confidence", entry.getConfidence());
            row.put("method", entry.getMethod());
            // Why the platform decided as it did, which is the question a person asks first.
            row.put("rationale", entry.getRationale());
            row.put("current", entry.isCurrent());
            row.put("createdBy", text(entry.getCreatedBy()));
            row.put("createdAt", entry.getCreatedAt() == null ? null : entry.getCreatedAt().toString());
            return row;
        }).toList();
    }

    /**
     * The values extracted from a document, each with where it came from.
     *
     * <p>The page and the sentence are the point: a figure somebody is asked to confirm has to be checkable
     * against the letter it came out of, and a value with no hint is marked as such.</p>
     */
    @GetMapping("/{documentId}/fields")
    @PreAuthorize(SEE)
    @Operation(summary = "Extracted values, each with the page and sentence it came from")
    public Map<String, Object> fields(@PathVariable UUID documentId) {
        RequestContext.tenantId().orElseThrow();
        List<DocumentExtraction> extracted = fields.valuesOf(documentId);

        List<Map<String, Object>> values = extracted.stream().map(entry -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("extractionId", text(entry.getId()));
            row.put("fieldKey", entry.getFieldKey());
            row.put("rawValue", entry.getFieldValue());
            row.put("normalizedValue", entry.getNormalizedValue());
            row.put("valueType", entry.getValueType());
            row.put("confidence", entry.getConfidence());
            row.put("extractor", entry.getExtractor());
            row.put("sourceKind", entry.getSourceKind());
            row.put("sourcePage", entry.getSourcePage());
            row.put("sourceSnippet", entry.getSourceSnippet());
            // A value without a hint is one somebody has to take on trust; the answer says which it is
            // rather than presenting both the same way.
            row.put("verifiable", entry.getSourcePage() != null || entry.getSourceSnippet() != null);
            // Whether a person has checked this value, which is a different statement from the value
            // itself being present.
            row.put("verified", entry.isVerified());
            row.put("verifiedAt", entry.getVerifiedAt() == null ? null : entry.getVerifiedAt().toString());
            return row;
        }).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("documentId", documentId.toString());
        result.put("fields", values);
        result.put("unverifiableCount", values.stream()
                .filter(row -> Boolean.FALSE.equals(row.get("verifiable"))).count());
        return result;
    }

    /** The confidence below which a document goes to a person, so a screen can explain the threshold. */
    @GetMapping("/classification/review-threshold")
    @PreAuthorize(SEE)
    @Operation(summary = "The confidence below which a document goes to a person")
    public Map<String, Object> reviewThreshold() {
        return Map.of("reviewThreshold", classifications.reviewThreshold(),
                "note", "Unterhalb dieses Werts wird ein Dokument zur Prüfung vorgelegt.");
    }

    private static String text(UUID value) {
        return value == null ? null : value.toString();
    }
}
