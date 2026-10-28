package de.buerokratiecopilot.agents.documents;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.documents.DocumentBlobRepository;
import de.buerokratiecopilot.documents.DocumentExtractionRepository;
import de.buerokratiecopilot.documents.DocumentPageRepository;
import de.buerokratiecopilot.documents.DocumentRepository;
import de.buerokratiecopilot.documents.DocumentTypeRepository;
import de.buerokratiecopilot.documents.DocumentVersionRepository;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.support.TestDataFactory;

/**
 * {@code doc-extract} against the real database: fields are extracted and stored, the document
 * metadata is filled only where empty, and a second run creates no duplicates.
 */
@SpringBootTest
@ActiveProfiles("test")
class DocExtractAgentIT {

    @Autowired
    private DocExtractAgent agent;

    @Autowired
    private TestDataFactory factory;

    @Autowired
    private DocumentRepository documents;

    @Autowired
    private DocumentBlobRepository blobs;

    @Autowired
    private DocumentVersionRepository versions;

    @Autowired
    private DocumentPageRepository pages;

    @Autowired
    private DocumentExtractionRepository extractions;

    @Autowired
    private DocumentTypeRepository types;

    @Test
    @DisplayName("the agent extracts declared fields and fills the document metadata idempotently")
    void extractsFieldsAndMetadataIdempotently() {
        UUID tenantId = DocumentsAgentItSupport.seedTenantWithUser(factory);
        UUID documentId = DocumentsAgentItSupport.seedDocumentWithText(documents, blobs, versions, pages,
                tenantId, "Rechnung.pdf",
                "Rechnungsnummer 2026-117\nRechnungsdatum 12.10.2026\n"
                        + "Bruttobetrag 1.190,00 EUR\nAbsender: Stadtwerke GmbH\nAn: Max Mustermann",
                Document.ProcessingStatus.RECEIVED);
        RequestContext.runAs(tenantId, () -> {
            Document document = documents.findById(documentId).orElseThrow();
            document.setDocumentTypeId(types.findByCode("rechnung").orElseThrow().getId());
            documents.save(document);
        });

        AgentContext context = new AgentContext(tenantId, null, "manual", null,
                Map.of("documentId", documentId.toString()));
        AgentResult result = agent.run(context);

        assertThat(result.ok()).isTrue();
        assertThat(result.meta().get("detectedAmount")).isEqualTo("1190.00");

        Document stored = RequestContext.runAs(tenantId,
                () -> documents.findById(documentId).orElseThrow());
        assertThat(stored.getDocumentNumber()).isEqualTo("2026-117");
        assertThat(stored.getDocumentDate()).isEqualTo(java.time.LocalDate.of(2026, 10, 12));
        assertThat(stored.getSender()).isEqualTo("Stadtwerke GmbH");
        assertThat(stored.getRecipient()).isEqualTo("Max Mustermann");
        assertThat(RequestContext.runAs(tenantId,
                () -> extractions.findByDocumentId(documentId)))
                .extracting(de.buerokratiecopilot.documents.domain.DocumentExtraction::getFieldKey)
                .contains("Rechnungsnummer", "Rechnungsdatum", "Brutto");

        // Idempotence: a second run stores the same values and does not double the extraction rows.
        int rowsAfterFirst = RequestContext.runAs(tenantId,
                () -> extractions.findByDocumentId(documentId).size());
        AgentResult second = agent.run(context);
        assertThat(second.ok()).isTrue();
        int rowsAfterSecond = RequestContext.runAs(tenantId,
                () -> extractions.findByDocumentId(documentId).size());
        assertThat(rowsAfterSecond).isEqualTo(rowsAfterFirst);

        Document again = RequestContext.runAs(tenantId,
                () -> documents.findById(documentId).orElseThrow());
        assertThat(again.getDocumentNumber()).isEqualTo("2026-117");
        assertThat(again.getSender()).isEqualTo("Stadtwerke GmbH");
    }

    @Test
    @DisplayName("a document without text is reported honestly")
    void documentWithoutTextIsSkipped() {
        UUID tenantId = DocumentsAgentItSupport.seedTenantWithUser(factory);
        UUID documentId = DocumentsAgentItSupport.seedDocumentWithText(documents, blobs, versions, pages,
                tenantId, "scan.pdf", "", Document.ProcessingStatus.RECEIVED);

        AgentResult result = agent.run(new AgentContext(tenantId, null, "manual", null,
                Map.of("documentId", documentId.toString())));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("keinen Text");
    }

    @Test
    @DisplayName("an unknown document is reported, not guessed at")
    void unknownDocumentFails() {
        UUID tenantId = DocumentsAgentItSupport.seedTenantWithUser(factory);
        AgentResult result = agent.run(new AgentContext(tenantId, null, "manual", null,
                Map.of("documentId", UUID.randomUUID().toString())));
        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("nicht gefunden");
    }
}
