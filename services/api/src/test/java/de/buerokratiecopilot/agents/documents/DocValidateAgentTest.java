package de.buerokratiecopilot.agents.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

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
import de.buerokratiecopilot.documents.domain.DocumentType;
import de.buerokratiecopilot.documents.domain.DocumentTypeField;

/**
 * {@code doc-validate}: required fields, plausibility and completeness produce a verifiable list
 * of problems — and a clean document produces none.
 */
class DocValidateAgentTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();
    private final UUID typeId = UUID.randomUUID();

    private DocumentRepository documents;
    private DocumentPageRepository pages;
    private DocumentExtractionRepository extractions;
    private DocumentTypeFieldRepository fieldDefinitions;
    private DocumentTypeRepository types;
    private DocumentReviewRepository reviews;

    @BeforeEach
    void setUp() {
        documents = mock(DocumentRepository.class);
        pages = mock(DocumentPageRepository.class);
        extractions = mock(DocumentExtractionRepository.class);
        fieldDefinitions = mock(DocumentTypeFieldRepository.class);
        types = mock(DocumentTypeRepository.class);
        reviews = mock(DocumentReviewRepository.class);
    }

    private Document document() {
        Document document = new Document(documentId, tenantId, "Rechnung.pdf", "stored/r.pdf",
                "application/pdf", 900, "c".repeat(64), Document.Source.UPLOAD);
        document.setDocumentTypeId(typeId);
        return document;
    }

    private AgentContext ctx() {
        return new AgentContext(tenantId, UUID.randomUUID(), "manual",
                Instant.parse("2026-10-26T12:00:00Z"), Map.of("documentId", documentId.toString()));
    }

    private DocumentTypeField requiredField(String key) {
        DocumentTypeField field = mock(DocumentTypeField.class);
        when(field.isRequired()).thenReturn(true);
        when(field.getFieldKey()).thenReturn(key);
        return field;
    }

    private DocumentExtraction extraction(String key, String type, String value, String normalized) {
        DocumentExtraction row = new DocumentExtraction(UUID.randomUUID(), documentId, null, key,
                type, "RULE", "TEXT");
        row.setFieldValue(value);
        row.setNormalizedValue(normalized);
        return row;
    }

    @Test
    @DisplayName("required fields without a value, future dates and negative amounts are reported")
    void reportsProblems() {
        Document document = document();
        when(documents.findByIdAndTenantIdAndDeletedAtIsNull(documentId, tenantId))
                .thenReturn(Optional.of(document));
        when(pages.countByDocumentId(documentId)).thenReturn(1L);
        when(types.findByCode("unbekannt")).thenReturn(Optional.empty());
        List<DocumentTypeField> requiredFields = List.of(requiredField("Rechnungsdatum"),
                requiredField("Brutto"));
        when(fieldDefinitions.findByDocumentTypeIdOrderBySortOrderAsc(typeId))
                .thenReturn(requiredFields);
        when(extractions.findByDocumentId(documentId)).thenReturn(List.of(
                extraction("Rechnungsdatum", "DATE", "31.12.2027", "2027-12-31"),
                extraction("Brutto", "AMOUNT", "-10,00", "-10.00")));
        when(reviews.findFirstByDocumentIdAndStatusIn(documentId,
                List.of(DocumentReview.Status.PENDING, DocumentReview.Status.IN_REVIEW)))
                .thenReturn(Optional.empty());

        DocValidateAgent agent = new DocValidateAgent(documents, pages, extractions,
                fieldDefinitions, types, reviews, new ObjectMapper());
        AgentResult result = agent.run(ctx());

        assertThat(result.ok()).isTrue();
        assertThat(result.meta().get("problemsCount")).isNotEqualTo("0");
        assertThat(result.meta().get("problems")).contains("future_date")
                .contains("negative_amount")
                .contains("missing_document_number");
        assertThat(result.summary()).contains("Problem");
    }

    @Test
    @DisplayName("a complete and plausible document yields an explicit all-clear")
    void cleanDocumentYieldsNoProblems() {
        Document document = document();
        document.setDocumentDate(java.time.LocalDate.of(2026, 10, 12));
        document.setDocumentNumber("RE-2026-0042");
        when(documents.findByIdAndTenantIdAndDeletedAtIsNull(documentId, tenantId))
                .thenReturn(Optional.of(document));
        when(pages.countByDocumentId(documentId)).thenReturn(1L);
        DocumentType type = mock(DocumentType.class);
        when(type.getId()).thenReturn(typeId);
        when(types.findByCode("unbekannt")).thenReturn(Optional.empty());
        List<DocumentTypeField> requiredFields = List.of(requiredField("Rechnungsdatum"));
        when(fieldDefinitions.findByDocumentTypeIdOrderBySortOrderAsc(typeId))
                .thenReturn(requiredFields);
        when(extractions.findByDocumentId(documentId)).thenReturn(List.of(
                extraction("Rechnungsdatum", "DATE", "12.10.2026", "2026-10-12")));
        when(reviews.findFirstByDocumentIdAndStatusIn(documentId,
                List.of(DocumentReview.Status.PENDING, DocumentReview.Status.IN_REVIEW)))
                .thenReturn(Optional.empty());

        DocValidateAgent agent = new DocValidateAgent(documents, pages, extractions,
                fieldDefinitions, types, reviews, new ObjectMapper());
        AgentResult result = agent.run(ctx());

        assertThat(result.ok()).isTrue();
        assertThat(result.meta().get("problemsCount")).isEqualTo("0");
        assertThat(result.summary()).contains("Keine Probleme");
    }

    @Test
    @DisplayName("a document that does not exist is reported, not validated")
    void unknownDocumentFails() {
        when(documents.findByIdAndTenantIdAndDeletedAtIsNull(documentId, tenantId))
                .thenReturn(Optional.empty());
        DocValidateAgent agent = new DocValidateAgent(documents, pages, extractions,
                fieldDefinitions, types, reviews, new ObjectMapper());
        AgentResult result = agent.run(ctx());
        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("nicht gefunden");
    }
}
