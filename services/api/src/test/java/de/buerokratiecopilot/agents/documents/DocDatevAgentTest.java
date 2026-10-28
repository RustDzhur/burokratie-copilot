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

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.documents.DocumentExtractionRepository;
import de.buerokratiecopilot.documents.DocumentRepository;
import de.buerokratiecopilot.documents.DocumentTypeRepository;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.documents.domain.DocumentExtraction;
import de.buerokratiecopilot.documents.domain.DocumentType;

/**
 * {@code doc-datev}: the agent hands documents to the packager and reports the sealed package —
 * or refuses when none of the documents exist.
 */
class DocDatevAgentTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();
    private final UUID typeId = UUID.randomUUID();

    private DocumentRepository documents;
    private DocumentExtractionRepository extractions;
    private DocumentTypeRepository types;

    @BeforeEach
    void setUp() {
        documents = mock(DocumentRepository.class);
        extractions = mock(DocumentExtractionRepository.class);
        types = mock(DocumentTypeRepository.class);
    }

    private Document document() {
        Document document = new Document(documentId, tenantId, "Rechnung_RE-2026-0042.pdf",
                "stored/r42.pdf", "application/pdf", 900, "e".repeat(64), Document.Source.UPLOAD);
        document.setDocumentTypeId(typeId);
        document.setDocumentDate(java.time.LocalDate.of(2026, 10, 12));
        document.setDocumentNumber("RE-2026-0042");
        return document;
    }

    private DocumentExtraction amount(String value) {
        DocumentExtraction row = new DocumentExtraction(UUID.randomUUID(), documentId, null,
                "Brutto", "AMOUNT", "RULE", "TEXT");
        row.setFieldValue(value);
        row.setNormalizedValue(value);
        return row;
    }

    private AgentContext ctx(String ids) {
        return new AgentContext(tenantId, UUID.randomUUID(), "manual",
                Instant.parse("2026-10-26T12:00:00Z"), Map.of("documentIds", ids));
    }

    @Test
    @DisplayName("two documents become a sealed package with a stable checksum")
    void buildsPackageWithChecksum() {
        when(documents.findByIdAndTenantIdAndDeletedAtIsNull(documentId, tenantId))
                .thenReturn(Optional.of(document()));
        DocumentType type = mock(DocumentType.class);
        when(type.getCode()).thenReturn("invoice");
        when(types.findById(typeId)).thenReturn(Optional.of(type));
        when(extractions.findByDocumentId(documentId)).thenReturn(List.of(amount("1190.00")));

        DocDatevAgent agent = new DocDatevAgent(documents, extractions, types);
        AgentResult result = agent.run(ctx(documentId.toString()));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta().get("lines")).isEqualTo("1");
        assertThat(result.meta().get("checksum")).matches("[0-9a-f]{64}");
        assertThat(result.meta().get("csv")).contains("2026-10-12;RE-2026-0042;1600;1600;1190.00");
        assertThat(result.summary()).contains("Kontrollsumme");
    }

    @Test
    @DisplayName("warnings travel with the package; a missing amount is not invented")
    void warningsTravelWithThePackage() {
        Document document = document();
        document.setDocumentNumber(null);
        when(documents.findByIdAndTenantIdAndDeletedAtIsNull(documentId, tenantId))
                .thenReturn(Optional.of(document));
        DocumentType type = mock(DocumentType.class);
        when(type.getCode()).thenReturn("invoice");
        when(types.findById(typeId)).thenReturn(Optional.of(type));
        when(extractions.findByDocumentId(documentId)).thenReturn(List.of());

        DocDatevAgent agent = new DocDatevAgent(documents, extractions, types);
        AgentResult result = agent.run(ctx(documentId.toString()));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta().get("warnings")).isEqualTo("2");
        assertThat(result.summary()).contains("2 Warnung");
    }

    @Test
    @DisplayName("without any existing document the package is refused, not invented")
    void missingDocumentsFail() {
        when(documents.findByIdAndTenantIdAndDeletedAtIsNull(documentId, tenantId))
                .thenReturn(Optional.empty());
        DocDatevAgent agent = new DocDatevAgent(documents, extractions, types);
        AgentResult result = agent.run(ctx(documentId.toString()));
        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("kein");
    }

    @Test
    @DisplayName("without document ids the agent reports honestly")
    void noIdsSkipped() {
        DocDatevAgent agent = new DocDatevAgent(documents, extractions, types);
        AgentResult result = agent.run(ctx(""));
        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("documentIds");
    }
}
