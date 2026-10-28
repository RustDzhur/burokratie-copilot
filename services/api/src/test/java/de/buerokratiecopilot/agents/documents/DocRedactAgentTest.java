package de.buerokratiecopilot.agents.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import de.buerokratiecopilot.documents.DocumentPageRepository;
import de.buerokratiecopilot.documents.DocumentRepository;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.documents.domain.DocumentPage;

/**
 * {@code doc-redact}: the plan is handed back with positions, and nothing is changed — the agent
 * writes no rows, the produced entry says so.
 */
class DocRedactAgentTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();
    private final UUID versionId = UUID.randomUUID();

    private DocumentRepository documents;
    private DocumentPageRepository pages;

    @BeforeEach
    void setUp() {
        documents = mock(DocumentRepository.class);
        pages = mock(DocumentPageRepository.class);
    }

    private Document document() {
        return new Document(documentId, tenantId, "Brief.pdf", "stored/brief.pdf",
                "application/pdf", 800, "d".repeat(64), Document.Source.UPLOAD);
    }

    private AgentContext ctx() {
        return new AgentContext(tenantId, UUID.randomUUID(), "manual", null,
                Map.of("documentId", documentId.toString()));
    }

    @Test
    @DisplayName("personal data on the pages becomes a plan with positions; nothing is persisted")
    void producesPlanWithoutChangingAnything() {
        when(documents.findByIdAndTenantIdAndDeletedAtIsNull(documentId, tenantId))
                .thenReturn(Optional.of(document()));
        DocumentPage page = new DocumentPage(UUID.randomUUID(), documentId, versionId, 1);
        page.setTextContent("Kontakt: max.mustermann@example.de, Tel. +49 30 123456\n"
                + "IBAN DE89 3704 0044 0532 0130 00\nAnschrift: 10115 Berlin");
        when(pages.findByDocumentIdOrderByPageNumberAsc(documentId)).thenReturn(List.of(page));

        DocRedactAgent agent = new DocRedactAgent(documents, pages, new ObjectMapper());
        AgentResult result = agent.run(ctx());

        assertThat(result.ok()).isTrue();
        assertThat(result.meta().get("findingsCount")).isEqualTo("4");
        assertThat(result.meta().get("findings")).contains("EMAIL")
                .contains("PHONE").contains("IBAN").contains("ADDRESS");
        assertThat(result.summary()).contains("nichts verändert");
        assertThat(result.produced()).anySatisfy(produced -> {
            assertThat(produced.kind()).isEqualTo("redaction-plan");
            assertThat(produced.ref()).isEqualTo(documentId.toString());
        });
    }

    @Test
    @DisplayName("text without personal data yields an all-clear plan")
    void cleanTextYieldsEmptyPlan() {
        when(documents.findByIdAndTenantIdAndDeletedAtIsNull(documentId, tenantId))
                .thenReturn(Optional.of(document()));
        DocumentPage page = new DocumentPage(UUID.randomUUID(), documentId, versionId, 1);
        page.setTextContent("Sehr geehrte Damen und Herren, vielen Dank für Ihr Schreiben.");
        when(pages.findByDocumentIdOrderByPageNumberAsc(documentId)).thenReturn(List.of(page));

        DocRedactAgent agent = new DocRedactAgent(documents, pages, new ObjectMapper());
        AgentResult result = agent.run(ctx());

        assertThat(result.ok()).isTrue();
        assertThat(result.meta().get("findingsCount")).isEqualTo("0");
        assertThat(result.summary()).contains("Keine personenbezogenen Daten");
    }

    @Test
    @DisplayName("a document without any text is skipped honestly")
    void noTextIsSkipped() {
        when(documents.findByIdAndTenantIdAndDeletedAtIsNull(documentId, tenantId))
                .thenReturn(Optional.of(document()));
        when(pages.findByDocumentIdOrderByPageNumberAsc(documentId)).thenReturn(List.of());

        DocRedactAgent agent = new DocRedactAgent(documents, pages, new ObjectMapper());
        AgentResult result = agent.run(ctx());

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("keinen Text");
    }
}
