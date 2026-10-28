package de.buerokratiecopilot.agents.documents;

import static org.assertj.core.api.Assertions.assertThat;

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
import de.buerokratiecopilot.documents.DocumentVersionRepository;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.support.TestDataFactory;

/**
 * {@code doc-archive} against the real database: a checked document is archived with a date, an
 * already archived one is reported, and legal holds and impossible transitions are refused.
 */
@SpringBootTest
@ActiveProfiles("test")
class DocArchiveAgentIT {

    @Autowired
    private DocArchiveAgent agent;

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

    @Test
    @DisplayName("a ready document without legal hold and without open review is archived once")
    void archivesReadyDocument() {
        UUID tenantId = DocumentsAgentItSupport.seedTenantWithUser(factory);
        UUID documentId = DocumentsAgentItSupport.seedDocumentWithText(documents, blobs, versions, pages,
                tenantId, "Rechnung.pdf", "Rechnungsnummer 2026-117", Document.ProcessingStatus.READY);

        AgentContext context = new AgentContext(tenantId, null, "manual", null,
                Map.of("documentId", documentId.toString()));
        AgentResult result = agent.run(context);

        assertThat(result.ok()).isTrue();
        Document stored = RequestContext.runAs(tenantId,
                () -> documents.findById(documentId).orElseThrow());
        assertThat(stored.getProcessingStatus()).isEqualTo(Document.ProcessingStatus.ARCHIVED);
        assertThat(stored.getArchivedAt()).isNotNull();

        // Idempotence: the archived document is reported, not archived a second time.
        AgentResult second = agent.run(context);
        assertThat(second.ok()).isFalse();
        assertThat(second.summary()).contains("bereits archiviert");
    }

    @Test
    @DisplayName("a document under legal hold is never archived")
    void legalHoldIsRefused() {
        UUID tenantId = DocumentsAgentItSupport.seedTenantWithUser(factory);
        UUID documentId = DocumentsAgentItSupport.seedDocumentWithText(documents, blobs, versions, pages,
                tenantId, "Vertrag.pdf", "Text", Document.ProcessingStatus.READY);
        RequestContext.runAs(tenantId, () -> {
            Document document = documents.findById(documentId).orElseThrow();
            document.setLegalHold(true);
            documents.save(document);
        });

        AgentResult result = agent.run(new AgentContext(tenantId, null, "manual", null,
                Map.of("documentId", documentId.toString())));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("Legal Hold");
        Document stored = RequestContext.runAs(tenantId,
                () -> documents.findById(documentId).orElseThrow());
        assertThat(stored.getProcessingStatus()).isEqualTo(Document.ProcessingStatus.READY);
    }

    @Test
    @DisplayName("a document whose status cannot move to ARCHIVED is refused with the reason")
    void impossibleTransitionIsRefused() {
        UUID tenantId = DocumentsAgentItSupport.seedTenantWithUser(factory);
        UUID documentId = DocumentsAgentItSupport.seedDocumentWithText(documents, blobs, versions, pages,
                tenantId, "Rechnung.pdf", "Text", Document.ProcessingStatus.RECEIVED);

        AgentResult result = agent.run(new AgentContext(tenantId, null, "manual", null,
                Map.of("documentId", documentId.toString())));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("nicht archivreif");
        assertThat(result.summary()).contains("erlaubt keine Archivierung");
    }

    @Test
    @DisplayName("an unknown document is reported, not archived")
    void unknownDocumentFails() {
        UUID tenantId = DocumentsAgentItSupport.seedTenantWithUser(factory);
        AgentResult result = agent.run(new AgentContext(tenantId, null, "manual", null,
                Map.of("documentId", UUID.randomUUID().toString())));
        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("nicht gefunden");
    }
}
