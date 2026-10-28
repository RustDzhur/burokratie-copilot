package de.buerokratiecopilot.agents.documents;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
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
import de.buerokratiecopilot.documents.DocumentPageRepository;
import de.buerokratiecopilot.documents.DocumentRepository;
import de.buerokratiecopilot.documents.DocumentTypeRepository;
import de.buerokratiecopilot.documents.DocumentVersionRepository;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.support.TestDataFactory;

/**
 * {@code doc-validate} against the real database, after a real extraction run: the problems are
 * computed from stored values, and a clean document gets an explicit all-clear.
 */
@SpringBootTest
@ActiveProfiles("test")
class DocValidateAgentIT {

    @Autowired
    private DocValidateAgent agent;

    @Autowired
    private DocExtractAgent extractAgent;

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
    private DocumentTypeRepository types;

    private UUID seededInvoice(UUID tenantId, String text) {
        UUID documentId = DocumentsAgentItSupport.seedDocumentWithText(documents, blobs, versions, pages,
                tenantId, "Rechnung.pdf", text, Document.ProcessingStatus.RECEIVED);
        RequestContext.runAs(tenantId, () -> {
            Document document = documents.findById(documentId).orElseThrow();
            document.setDocumentTypeId(types.findByCode("rechnung").orElseThrow().getId());
            documents.save(document);
        });
        extractAgent.run(new AgentContext(tenantId, null, "manual", null,
                Map.of("documentId", documentId.toString())));
        return documentId;
    }

    @Test
    @DisplayName("missing required fields, a negative amount and a missing number are all reported")
    void reportsRealProblems() {
        UUID tenantId = DocumentsAgentItSupport.seedTenantWithUser(factory);
        UUID documentId = seededInvoice(tenantId,
                "Rechnungsdatum 12.10.2026\nBruttobetrag: -10,00 EUR");

        AgentResult result = agent.run(new AgentContext(tenantId, null, "manual",
                Instant.parse("2026-10-26T12:00:00Z"), Map.of("documentId", documentId.toString())));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta().get("problems")).contains("required_missing")
                .contains("negative_amount")
                .contains("missing_document_number");
        assertThat(Integer.parseInt(result.meta().get("problemsCount"))).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("a complete and plausible document yields the explicit all-clear")
    void cleanDocumentYieldsAllClear() {
        UUID tenantId = DocumentsAgentItSupport.seedTenantWithUser(factory);
        UUID documentId = seededInvoice(tenantId,
                "Rechnungsnummer RE-2026-0042\nRechnungsdatum 12.10.2026\n"
                        + "Lieferant Acme GmbH\nNettobetrag 100,00 EUR\nBruttobetrag 119,00 EUR");

        AgentResult result = agent.run(new AgentContext(tenantId, null, "manual",
                Instant.parse("2026-10-26T12:00:00Z"), Map.of("documentId", documentId.toString())));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta().get("problemsCount")).isEqualTo("0");
        assertThat(result.summary()).contains("Keine Probleme");
    }

    @Test
    @DisplayName("a document that does not exist is reported, not validated")
    void unknownDocumentFails() {
        UUID tenantId = DocumentsAgentItSupport.seedTenantWithUser(factory);
        AgentResult result = agent.run(new AgentContext(tenantId, null, "manual", null,
                Map.of("documentId", UUID.randomUUID().toString())));
        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("nicht gefunden");
    }
}
