package de.buerokratiecopilot.documents.fields;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.documents.DocumentExtractionRepository;
import de.buerokratiecopilot.documents.DocumentPageRepository;
import de.buerokratiecopilot.documents.DocumentTypeFieldRepository;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.documents.domain.DocumentExtraction;
import de.buerokratiecopilot.documents.domain.DocumentPage;
import de.buerokratiecopilot.documents.domain.DocumentTypeField;
import de.buerokratiecopilot.documents.domain.DocumentVersion;

/**
 * Reads the declared fields of a document type out of its pages (spec §9.4, §10).
 *
 * <p>How a value is found: the definition holds label variants ({@code Rechnungsnummer},
 * {@code Rechnungs-Nr}, ...), the service looks for the first one that occurs on a page, takes the
 * rest of that line and hands it to {@link FieldValueParser}. The label search is what makes the
 * result explainable — the platform can show a reviewer the exact line — and the parser is what makes
 * {@code 1.190,00} comparable with {@code 1190.00}.</p>
 *
 * <p>What this service refuses to do is equally important:</p>
 * <ul>
 *   <li>a value that fails its type check is not stored. A wrongly parsed amount looks like a fact,
 *       and a missing one looks like missing data — only the second is honest;</li>
 *   <li>a field without a hint is reported as not extracted, not as empty: it is filled by a human,
 *       and the review centre needs to know the difference;</li>
 *   <li>a field with several candidates keeps the first occurrence and records it. Choosing "the most
 *       likely one" would be a guess the platform cannot justify.</li>
 * </ul>
 */
@Service
public class FieldExtractionService {

    private static final Logger log = LoggerFactory.getLogger(FieldExtractionService.class);

    /** Confidence for a value that was found through its label and passed its type check. */
    private static final BigDecimal CONFIDENCE_VALIDATED = BigDecimal.valueOf(0.90);

    /**
     * Confidence for text without a checkable type. Lower than a validated value because nothing
     * confirms it beyond the label having been found.
     */
    private static final BigDecimal CONFIDENCE_TEXT = BigDecimal.valueOf(0.75);

    /** How much text around the value is stored as evidence. */
    private static final int SNIPPET_MARGIN = 60;

    /** The generic field set every type without its own fields uses (reference, amount, date, IBAN). */
    private static final String GENERIC_TYPE = "unbekannt";

    private final DocumentTypeFieldRepository definitions;
    private final DocumentPageRepository pages;
    private final DocumentExtractionRepository extractions;
    private final de.buerokratiecopilot.documents.DocumentClassificationRepository classifications;
    private final de.buerokratiecopilot.documents.DocumentTypeRepository types;

    public FieldExtractionService(DocumentTypeFieldRepository definitions, DocumentPageRepository pages,
                                  DocumentExtractionRepository extractions,
                                  de.buerokratiecopilot.documents.DocumentClassificationRepository classifications,
                                  de.buerokratiecopilot.documents.DocumentTypeRepository types) {
        this.definitions = definitions;
        this.pages = pages;
        this.extractions = extractions;
        this.classifications = classifications;
        this.types = types;
    }

    /**
     * The type whose field definitions are read: the document's own type; while a person has not yet
     * confirmed one, the type the platform suggests; and without even a suggestion, the generic field set.
     *
     * <p>This is what lets a reviewer see values instead of an empty table. Reading fields against a
     * suggested type is harmless because every value stays unverified until a person confirms it.</p>
     */
    private java.util.UUID definitionsTypeOf(Document document) {
        if (document.getDocumentTypeId() != null) {
            return document.getDocumentTypeId();
        }
        return classifications.findByDocumentIdAndCurrentTrue(document.getId())
                .map(de.buerokratiecopilot.documents.domain.DocumentClassification::getDocumentTypeId)
                .or(() -> types.findByCode(GENERIC_TYPE)
                        .map(de.buerokratiecopilot.documents.domain.DocumentType::getId))
                .orElse(null);
    }

    /** What the extraction stage found, per field. */
    public record Result(List<Found> found, List<String> notFound, List<String> withoutHint) {

        public boolean isEmpty() {
            return found.isEmpty();
        }

        public Map<String, Object> diagnostics() {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("fieldsWithValues", found.size());
            details.put("fieldsNotFound", notFound.size());
            details.put("fieldsWithoutHint", withoutHint.size());
            details.put("notFound", String.join(", ", notFound));
            details.put("extracted", String.join(", ",
                    found.stream().map(found -> found.fieldKey() + "=" + found.value()).toList()));
            return details;
        }
    }

    /** One extracted value with its provenance. */
    public record Found(String fieldKey, String value, String normalized, int page, String snippet) {
    }

    @Transactional
    public Result extract(Document document, DocumentVersion version) {
        UUID typeId = definitionsTypeOf(document);
        if (typeId == null) {
            return new Result(List.of(), List.of(), List.of());
        }

        List<DocumentPage> documentPages = pages.findByVersionIdOrderByPageNumberAsc(version.getId());
        if (documentPages.isEmpty()) {
            return new Result(List.of(), List.of(), List.of());
        }

        List<DocumentTypeField> fields = definitions.findByDocumentTypeIdOrderBySortOrderAsc(typeId);

        // A value a person has confirmed or corrected is never overwritten by a new reading: re-running the
        // extraction (after the type was confirmed, say) must not undo the reviewer's work.
        List<DocumentExtraction> existing = extractions.findByVersionId(version.getId());
        java.util.Set<String> verifiedKeys = new java.util.HashSet<>();
        List<DocumentExtraction> stale = new ArrayList<>();
        for (DocumentExtraction row : existing) {
            if (row.getVerifiedBy() != null) {
                verifiedKeys.add(row.getFieldKey());
            }
            else {
                stale.add(row);
            }
        }
        extractions.deleteAll(stale);
        extractions.flush();

        List<Found> found = new ArrayList<>();
        List<String> notFound = new ArrayList<>();
        List<String> withoutHint = new ArrayList<>();

        for (DocumentTypeField field : fields) {
            if (verifiedKeys.contains(field.getFieldKey())) {
                continue;
            }
            List<String> labels = field.labels();
            if (labels.isEmpty()) {
                withoutHint.add(field.getFieldKey());
                continue;
            }
            Optional<Candidate> candidate = search(documentPages, labels);
            if (candidate.isEmpty()) {
                notFound.add(field.getFieldKey());
                continue;
            }

            Candidate raw = candidate.get();
            FieldValueParser.Parsed parsed = FieldValueParser.parse(raw.value(), field.getValueType());
            if (!parsed.valid()) {
                // The label was found but the value does not hold up: record it as missing and say
                // why in the log, instead of storing something the platform cannot stand behind.
                log.debug("Field {} of document {} was found but not accepted: {}", field.getFieldKey(),
                        document.getId(), parsed.reason());
                notFound.add(field.getFieldKey());
                continue;
            }

            DocumentExtraction entity = new DocumentExtraction(UUID.randomUUID(), document.getId(),
                    version.getId(), field.getFieldKey(), field.getValueType(), "RULE", raw.sourceKind());
            entity.setFieldValue(parsed.original());
            entity.setNormalizedValue(parsed.normalized());
            entity.setSourcePage(raw.page());
            entity.setSourceSnippet(raw.snippet());
            entity.setConfidence(FieldValueParser.isVerifiable(field.getValueType())
                    ? CONFIDENCE_VALIDATED
                    : CONFIDENCE_TEXT);
            extractions.save(entity);

            found.add(new Found(field.getFieldKey(), parsed.original(), parsed.normalized(), raw.page(),
                    raw.snippet()));
        }

        log.info("Extracted {} of {} declared field(s) for document {}", found.size(), fields.size(),
                document.getId());
        return new Result(found, notFound, withoutHint);
    }

    /** The value found for a label, with the page it came from and the line as evidence. */
    private record Candidate(String value, int page, String snippet, String sourceKind) {
    }

    /**
     * Looks for the first label that occurs, page by page and label by label in their declared order.
     *
     * <p>Page order is preserved, so a value is attributed to the page a reader would find it on; the
     * declared label order decides which variant wins when a document contains several.</p>
     */
    private Optional<Candidate> search(List<DocumentPage> documentPages, List<String> labels) {
        for (String label : labels) {
            for (DocumentPage page : documentPages) {
                String text = page.getTextContent();
                if (text == null || text.isBlank()) {
                    continue;
                }
                Optional<Candidate> candidate = findAfterLabel(text, label, page);
                if (candidate.isPresent()) {
                    return candidate;
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Finds the label on its own line and returns what follows it.
     *
     * <p>The label must be followed by a separator or whitespace, otherwise {@code Frist} would match
     * inside {@code Fristverlängerung} and produce a value from the wrong place.</p>
     */
    private Optional<Candidate> findAfterLabel(String text, String label, DocumentPage page) {
        String lowered = text.toLowerCase(Locale.GERMAN);
        String needle = label.toLowerCase(Locale.GERMAN);
        int from = 0;

        while (true) {
            int index = lowered.indexOf(needle, from);
            if (index < 0) {
                return Optional.empty();
            }
            int after = index + needle.length();
            boolean boundaryBefore = index == 0
                    || !Character.isLetterOrDigit(lowered.charAt(index - 1));
            boolean boundaryAfter = after >= lowered.length()
                    || !Character.isLetterOrDigit(lowered.charAt(after));
            if (boundaryBefore && boundaryAfter) {
                int lineEnd = lowered.indexOf('\n', after);
                String rest = text.substring(after, lineEnd < 0 ? text.length() : lineEnd);
                String value = rest.replaceFirst("^\\s*[:\\-–—]?\\s*", "").strip();
                if (!value.isEmpty()) {
                    return Optional.of(new Candidate(value, page.getPageNumber(),
                            snippet(text, index, after), page.isOcrUsed() ? "OCR" : "TEXT"));
                }
            }
            from = index + 1;
        }
    }

    /** The line around the value, so a reviewer sees the same words the extractor did. */
    private static String snippet(String text, int labelStart, int valueStart) {
        int lineStart = Math.max(0, text.lastIndexOf('\n', labelStart) + 1);
        int lineEnd = text.indexOf('\n', valueStart);
        if (lineEnd < 0) {
            lineEnd = text.length();
        }
        String line = text.substring(lineStart, lineEnd).strip();
        if (line.length() <= SNIPPET_MARGIN * 2) {
            return line;
        }
        int relative = Math.max(0, labelStart - lineStart - SNIPPET_MARGIN);
        int end = Math.min(line.length(), relative + SNIPPET_MARGIN * 2);
        return (relative > 0 ? "…" : "") + line.substring(relative, end).strip()
                + (end < line.length() ? "…" : "");
    }

    /** Values of a document as stored, for the review centre and the case view. */
    @Transactional(readOnly = true)
    public List<DocumentExtraction> valuesOf(UUID documentId) {
        return extractions.findByDocumentIdOrderByFieldKeyAsc(documentId);
    }

    /** Marks a value as confirmed by a person (spec §9.4). */
    @Transactional
    public DocumentExtraction confirm(DocumentExtraction extraction, UUID userId, String value) {
        if (value != null && !value.isBlank()) {
            extraction.setFieldValue(value);
            FieldValueParser.Parsed parsed = FieldValueParser.parse(value, extraction.getValueType());
            extraction.setNormalizedValue(parsed.valid() ? parsed.normalized() : null);
            extraction.setConfidence(BigDecimal.ONE.setScale(4, RoundingMode.HALF_UP));
        }
        extraction.setVerifiedBy(userId);
        extraction.setVerifiedAt(java.time.Instant.now());
        return extractions.save(extraction);
    }
}
