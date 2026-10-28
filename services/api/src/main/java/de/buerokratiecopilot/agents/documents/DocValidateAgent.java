package de.buerokratiecopilot.agents.documents;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.documents.DocumentExtractionRepository;
import de.buerokratiecopilot.documents.DocumentPageRepository;
import de.buerokratiecopilot.documents.DocumentRepository;
import de.buerokratiecopilot.documents.DocumentReviewRepository;
import de.buerokratiecopilot.documents.DocumentTypeFieldRepository;
import de.buerokratiecopilot.documents.DocumentTypeRepository;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.documents.domain.DocumentExtraction;
import de.buerokratiecopilot.documents.domain.DocumentReview;
import de.buerokratiecopilot.documents.domain.DocumentTypeField;
import de.buerokratiecopilot.documents.fields.FieldValueParser;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * {@code doc-validate} — Dokument-Validierung.
 *
 * <p>Checks a document against three rule families and reports every problem it finds:
 * required fields of the document type without a value, implausible values (dates in the future,
 * negative amounts, invalid IBANs) and completeness gaps (no text, no date, no number, an open
 * review, a duplicate). The check itself always "ran"; the honest answer is the list of problems,
 * and a document without problems is stated as such.</p>
 */
@Component
public class DocValidateAgent implements Agent {

    private static final String GENERIC_TYPE = "unbekannt";

    private final DocumentRepository documents;
    private final DocumentPageRepository pages;
    private final DocumentExtractionRepository extractions;
    private final DocumentTypeFieldRepository fieldDefinitions;
    private final DocumentTypeRepository types;
    private final DocumentReviewRepository reviews;
    private final ObjectMapper mapper;

    public DocValidateAgent(DocumentRepository documents, DocumentPageRepository pages,
                            DocumentExtractionRepository extractions,
                            DocumentTypeFieldRepository fieldDefinitions,
                            DocumentTypeRepository types, DocumentReviewRepository reviews,
                            ObjectMapper mapper) {
        this.documents = documents;
        this.pages = pages;
        this.extractions = extractions;
        this.fieldDefinitions = fieldDefinitions;
        this.types = types;
        this.reviews = reviews;
        this.mapper = mapper;
    }

    /** One problem, machine-readable: a code, the field it is about and a German explanation. */
    public record Problem(String code, String field, String message) {
    }

    @Override
    public String id() {
        return "doc-validate";
    }

    @Override
    public AgentResult run(AgentContext context) {
        UUID documentId = parseUuid(context.input("documentId"));
        if (documentId == null) {
            return AgentResult.skipped("Keine documentId angegeben; es gibt nichts zu prüfen.");
        }
        return RequestContext.runAs(context.tenantId(), () -> validate(context, documentId));
    }

    private AgentResult validate(AgentContext context, UUID documentId) {
        Document document = documents.findByIdAndTenantIdAndDeletedAtIsNull(documentId, context.tenantId())
                .orElse(null);
        if (document == null) {
            return AgentResult.failed(
                    "Dokument " + documentId + " wurde nicht gefunden (oder gehört einem anderen Tenant).");
        }

        List<Problem> problems = new ArrayList<>();

        checkRequiredFields(document, problems);
        checkPlausibility(context, documentId, problems);
        checkCompleteness(document, problems);

        String problemsJson;
        try {
            problemsJson = mapper.writeValueAsString(problems);
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            problemsJson = "[]";
        }

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("documentId", document.getId().toString());
        meta.put("problemsCount", String.valueOf(problems.size()));
        meta.put("problems", problemsJson);

        String summary = problems.isEmpty()
                ? "Keine Probleme gefunden: Pflichtfelder, Plausibilität und Vollständigkeit sind in Ordnung."
                : problems.size() + " Problem(e) gefunden: "
                        + String.join("; ", problems.stream().limit(5).map(Problem::message).toList())
                        + (problems.size() > 5 ? " …" : "");

        return AgentResult.done(summary,
                List.of(new AgentResult.Produced("validation", document.getId().toString(), summary)),
                meta);
    }

    /** Required fields of the document type (or the generic set) must carry a value. */
    private void checkRequiredFields(Document document, List<Problem> problems) {
        List<DocumentTypeField> fields = fieldsOf(document);
        Map<String, DocumentExtraction> byKey = new LinkedHashMap<>();
        for (DocumentExtraction extraction : extractions.findByDocumentId(document.getId())) {
            byKey.put(extraction.getFieldKey(), extraction);
        }
        for (DocumentTypeField field : fields) {
            if (!field.isRequired()) {
                continue;
            }
            DocumentExtraction value = byKey.get(field.getFieldKey());
            if (value == null || value.getFieldValue() == null || value.getFieldValue().isBlank()) {
                problems.add(new Problem("required_missing", field.getFieldKey(),
                        "Pflichtfeld " + field.getFieldKey() + " hat keinen Wert"));
            }
        }
    }

    /** Plausibility: dates in the future, negative amounts, IBANs that do not survive the checksum. */
    private void checkPlausibility(AgentContext context, UUID documentId, List<Problem> problems) {
        LocalDate today = context.now().atZone(java.time.ZoneId.of("Europe/Berlin")).toLocalDate();
        for (DocumentExtraction extraction : extractions.findByDocumentId(documentId)) {
            String type = extraction.getValueType() == null ? "" : extraction.getValueType();
            String normalized = extraction.getNormalizedValue();
            switch (type.toUpperCase(java.util.Locale.ROOT)) {
                case "DATE" -> {
                    if (normalized != null) {
                        try {
                            LocalDate date = LocalDate.parse(normalized);
                            if (date.isAfter(today)) {
                                problems.add(new Problem("future_date", extraction.getFieldKey(),
                                        "Datum " + extraction.getFieldKey() + " liegt in der Zukunft ("
                                                + date + ")"));
                            }
                        }
                        catch (java.time.DateTimeException ex) {
                            problems.add(new Problem("invalid_date", extraction.getFieldKey(),
                                    "Datum " + extraction.getFieldKey()
                                            + " ist nicht lesbar (" + normalized + ")"));
                        }
                    }
                }
                case "AMOUNT", "NUMBER" -> {
                    if (normalized != null) {
                        try {
                            if (new BigDecimal(normalized).signum() < 0) {
                                problems.add(new Problem("negative_amount", extraction.getFieldKey(),
                                        "Betrag " + extraction.getFieldKey() + " ist negativ ("
                                                + extraction.getFieldValue() + ")"));
                            }
                        }
                        catch (NumberFormatException ex) {
                            problems.add(new Problem("invalid_amount", extraction.getFieldKey(),
                                    "Betrag " + extraction.getFieldKey() + " ist nicht lesbar ("
                                            + extraction.getFieldValue() + ")"));
                        }
                    }
                }
                case "IBAN" -> {
                    FieldValueParser.Parsed parsed = FieldValueParser.parse(extraction.getFieldValue(),
                            "IBAN");
                    if (!parsed.valid()) {
                        problems.add(new Problem("invalid_iban", extraction.getFieldKey(),
                                "IBAN " + extraction.getFieldKey() + " ist ungültig ("
                                        + extraction.getFieldValue() + ")"));
                    }
                }
                default -> {
                    // Text fields carry no plausibility rule beyond being present.
                }
            }
        }
    }

    /** Completeness: text, document date and number exist, no review is open, not a duplicate. */
    private void checkCompleteness(Document document, List<Problem> problems) {
        boolean hasText = pages.countByDocumentId(document.getId()) > 0
                || (document.getExtractedText() != null && !document.getExtractedText().isBlank());
        if (!hasText) {
            problems.add(new Problem("no_text", null,
                    "Das Dokument trägt keinen Text: es gibt weder Seiten noch extrahierten Text"));
        }
        if (document.getDocumentDate() == null) {
            problems.add(new Problem("missing_document_date", null, "Das Dokumentdatum fehlt"));
        }
        if (document.getDocumentNumber() == null || document.getDocumentNumber().isBlank()) {
            problems.add(new Problem("missing_document_number", null, "Die Dokumentnummer fehlt"));
        }
        if (document.getProcessingStatus() == Document.ProcessingStatus.DUPLICATE) {
            problems.add(new Problem("duplicate", null, "Das Dokument ist ein Duplikat"));
        }
        boolean openReview = reviews
                .findFirstByDocumentIdAndStatusIn(document.getId(),
                        List.of(DocumentReview.Status.PENDING, DocumentReview.Status.IN_REVIEW))
                .isPresent();
        if (openReview) {
            problems.add(new Problem("review_pending", null,
                    "Für das Dokument steht noch eine Prüfung aus"));
        }
    }

    private List<DocumentTypeField> fieldsOf(Document document) {
        if (document.getDocumentTypeId() != null) {
            List<DocumentTypeField> fields = fieldDefinitions
                    .findByDocumentTypeIdOrderBySortOrderAsc(document.getDocumentTypeId());
            if (!fields.isEmpty()) {
                return fields;
            }
        }
        return types.findByCode(GENERIC_TYPE)
                .map(type -> fieldDefinitions.findByDocumentTypeIdOrderBySortOrderAsc(type.getId()))
                .orElse(List.of());
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.strip());
        }
        catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
