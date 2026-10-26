package de.buerokratiecopilot.agents.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.documents.DocumentPageRepository;
import de.buerokratiecopilot.documents.DocumentRepository;
import de.buerokratiecopilot.documents.DocumentVersionRepository;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.documents.domain.DocumentPage;
import de.buerokratiecopilot.documents.domain.DocumentVersion;
import de.buerokratiecopilot.documents.fields.FieldExtractionService;

/**
 * {@code doc-extract}: the rules read real texts, the agent fills only empty fields and delegates
 * declared fields to the existing extraction engine.
 */
class DocExtractAgentTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();
    private final UUID versionId = UUID.randomUUID();

    private DocumentRepository documents;
    private DocumentVersionRepository versions;
    private DocumentPageRepository pages;
    private FieldExtractionService fieldExtraction;

    @BeforeEach
    void setUp() {
        documents = mock(DocumentRepository.class);
        versions = mock(DocumentVersionRepository.class);
        pages = mock(DocumentPageRepository.class);
        fieldExtraction = mock(FieldExtractionService.class);
    }

    private Document document() {
        return new Document(documentId, tenantId, "Rechnung_2026-117.pdf", "stored/117.pdf",
                "application/pdf", 1500, "b".repeat(64), Document.Source.UPLOAD);
    }

    private AgentContext ctx(String documentId) {
        return new AgentContext(tenantId, UUID.randomUUID(), "manual", null,
                Map.of("documentId", documentId));
    }

    @Test
    @DisplayName("the number rule reads the value behind the label and rejects a pure date")
    void numberRules() {
        assertThat(DocExtractAgent.findNumber("Rechnungsnummer 2026-117\nBetrag 10 EUR"))
                .isEqualTo("2026-117");
        assertThat(DocExtractAgent.findNumber("Rechnungs-Nr.: RE-2026/42"))
                .isEqualTo("RE-2026/42");
        assertThat(DocExtractAgent.findNumber("Aktenzeichen: AZ-777-22\nAnbei")).isEqualTo("AZ-777-22");
        assertThat(DocExtractAgent.findNumber("Rechnungsnummer 31.03.2026")).isNull();
        assertThat(DocExtractAgent.findNumber("keine Nummer im Text")).isNull();
    }

    @Test
    @DisplayName("the date rule reads German and ISO dates and skips impossible ones")
    void dateRules() {
        assertThat(DocExtractAgent.findDate("Bescheid vom 31.03.2026")).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(DocExtractAgent.findDate("Datum 2026-01-02")).isEqualTo(LocalDate.of(2026, 1, 2));
        assertThat(DocExtractAgent.findDate("Frist 31.02.2026, Datum 05.04.2026"))
                .isEqualTo(LocalDate.of(2026, 4, 5));
        assertThat(DocExtractAgent.findDate("kein Datum")).isNull();
    }

    @Test
    @DisplayName("the amount rule reads German thousands separators")
    void amountRules() {
        assertThat(DocExtractAgent.findAmount("Gesamt 1.190,00 EUR"))
                .isEqualByComparingTo(new BigDecimal("1190.00"));
        assertThat(DocExtractAgent.findAmount("Summe 49,90 €"))
                .isEqualByComparingTo(new BigDecimal("49.90"));
        assertThat(DocExtractAgent.findAmount("ohne Betrag")).isNull();
    }

    @Test
    @DisplayName("the party rule reads the sender and the recipient, also on the next line")
    void partyRules() {
        assertThat(DocExtractAgent.findParty("Absender: Stadtwerke GmbH\nBetrag",
                DocExtractAgent.SENDER_LABELS)).isEqualTo("Stadtwerke GmbH");
        assertThat(DocExtractAgent.findParty("An:\nMax Mustermann\nMusterstraße",
                DocExtractAgent.RECIPIENT_LABELS)).isEqualTo("Max Mustermann");
    }

    @Test
    @DisplayName("the agent fills number, date and parties only where they are empty")
    void fillsEmptyFieldsAndKeepsExistingOnes() {
        Document document = document();
        document.setSender("Bereits bekannt");
        when(documents.findByIdAndTenantIdAndDeletedAtIsNull(documentId, tenantId))
                .thenReturn(Optional.of(document));
        when(versions.findFirstByDocumentIdOrderByVersionDesc(documentId))
                .thenReturn(Optional.of(new DocumentVersion(versionId, documentId, tenantId, 1,
                        UUID.randomUUID(), "b".repeat(64), 1500, "application/pdf",
                        "Rechnung_2026-117.pdf")));
        DocumentPage page = new DocumentPage(UUID.randomUUID(), documentId, versionId, 1);
        page.setTextContent("Rechnungsnummer 2026-117\nAbsender: Stadtwerke GmbH\n"
                + "Betrag 1.190,00 EUR\nDatum 12.10.2026\nAn: Max Mustermann");
        when(pages.findByVersionIdOrderByPageNumberAsc(versionId)).thenReturn(List.of(page));
        when(fieldExtraction.extract(any(), any()))
                .thenReturn(new FieldExtractionService.Result(List.of(), List.of("Rechnungsnummer"),
                        List.of()));

        DocExtractAgent agent = new DocExtractAgent(documents, versions, pages, fieldExtraction);
        AgentResult result = agent.run(ctx(documentId.toString()));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta().get("detectedAmount")).isEqualTo("1190.00");
        verify(documents).save(document);
        assertThat(document.getDocumentNumber()).isEqualTo("2026-117");
        assertThat(document.getDocumentDate()).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(document.getRecipient()).isEqualTo("Max Mustermann");
        assertThat(document.getSender()).isEqualTo("Bereits bekannt");
    }

    @Test
    @DisplayName("a document without text yields an honest skip, not a fake success")
    void noTextIsSkipped() {
        Document document = document();
        when(documents.findByIdAndTenantIdAndDeletedAtIsNull(documentId, tenantId))
                .thenReturn(Optional.of(document));
        when(versions.findFirstByDocumentIdOrderByVersionDesc(documentId))
                .thenReturn(Optional.of(new DocumentVersion(versionId, documentId, tenantId, 1,
                        UUID.randomUUID(), "b".repeat(64), 1500, "application/pdf", "scan.pdf")));
        when(pages.findByVersionIdOrderByPageNumberAsc(versionId)).thenReturn(List.of());

        DocExtractAgent agent = new DocExtractAgent(documents, versions, pages, fieldExtraction);
        AgentResult result = agent.run(ctx(documentId.toString()));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("keinen Text");
    }

    @Test
    @DisplayName("an unknown document is reported, not guessed at")
    void unknownDocumentFails() {
        when(documents.findByIdAndTenantIdAndDeletedAtIsNull(documentId, tenantId))
                .thenReturn(Optional.empty());
        DocExtractAgent agent = new DocExtractAgent(documents, versions, pages, fieldExtraction);
        AgentResult result = agent.run(ctx(documentId.toString()));
        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("nicht gefunden");
    }
}
