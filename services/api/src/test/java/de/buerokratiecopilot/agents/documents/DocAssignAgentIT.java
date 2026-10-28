package de.buerokratiecopilot.agents.documents;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * {@code doc-assign} against the real database: the case reference in the page text links the
 * document automatically, an explicit caseId links on demand, and neither path doubles a link.
 */
@SpringBootTest
@ActiveProfiles("test")
class DocAssignAgentIT {

    @Autowired
    private DocAssignAgent agent;

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
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("the case reference on the page links the document automatically, exactly once")
    void autoLinksByCaseReference() {
        UUID tenantId = DocumentsAgentItSupport.seedTenantWithUser(factory);
        UUID caseId = DocumentsAgentItSupport.seedCase(jdbc, tenantId, "V-2026-0001",
                "Wohnungsreparatur");
        UUID documentId = DocumentsAgentItSupport.seedDocumentWithText(documents, blobs, versions, pages,
                tenantId, "Schreiben.pdf", "Unser Aktenzeichen: V-2026-0001\nSehr geehrte Damen",
                Document.ProcessingStatus.READY);

        AgentResult result = agent.run(new AgentContext(tenantId, null, "manual", null,
                Map.of("documentId", documentId.toString())));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta().get("autoLinked")).isEqualTo("1");
        assertThat(countLinks(tenantId, caseId, documentId)).isEqualTo(1);

        // Idempotence: a second run does not create a second link.
        AgentResult second = agent.run(new AgentContext(tenantId, null, "manual", null,
                Map.of("documentId", documentId.toString())));
        assertThat(second.ok()).isTrue();
        assertThat(countLinks(tenantId, caseId, documentId)).isEqualTo(1);
    }

    @Test
    @DisplayName("an explicit caseId links on demand and is not doubled by a re-run")
    void explicitCaseIdLinksOnce() {
        UUID tenantId = DocumentsAgentItSupport.seedTenantWithUser(factory);
        UUID caseId = DocumentsAgentItSupport.seedCase(jdbc, tenantId, "V-2026-0002", "Steuer");
        UUID documentId = DocumentsAgentItSupport.seedDocumentWithText(documents, blobs, versions, pages,
                tenantId, "Unterlagen.pdf", "ohne Referenz im Text",
                Document.ProcessingStatus.READY);

        AgentContext context = new AgentContext(tenantId, null, "manual", null,
                Map.of("documentId", documentId.toString(), "caseId", caseId.toString()));
        AgentResult result = agent.run(context);

        assertThat(result.ok()).isTrue();
        assertThat(result.meta().get("explicitLinked")).isEqualTo("1");
        assertThat(countLinks(tenantId, caseId, documentId)).isEqualTo(1);

        agent.run(context);
        assertThat(countLinks(tenantId, caseId, documentId)).isEqualTo(1);
    }

    @Test
    @DisplayName("an unknown caseId is refused instead of linking into the void")
    void unknownCaseIdFails() {
        UUID tenantId = DocumentsAgentItSupport.seedTenantWithUser(factory);
        UUID documentId = DocumentsAgentItSupport.seedDocumentWithText(documents, blobs, versions, pages,
                tenantId, "Unterlagen.pdf", "Text", Document.ProcessingStatus.READY);

        AgentResult result = agent.run(new AgentContext(tenantId, null, "manual", null,
                Map.of("documentId", documentId.toString(),
                        "caseId", UUID.randomUUID().toString())));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("nicht gefunden");
    }

    @Test
    @DisplayName("a document nothing matches yields an honest report, not a fake link")
    void noMatchIsHonest() {
        UUID tenantId = DocumentsAgentItSupport.seedTenantWithUser(factory);
        UUID documentId = DocumentsAgentItSupport.seedDocumentWithText(documents, blobs, versions, pages,
                tenantId, "Unterlagen.pdf", "Ein Schreiben ohne Aktenzeichen",
                Document.ProcessingStatus.READY);

        AgentResult result = agent.run(new AgentContext(tenantId, null, "manual", null,
                Map.of("documentId", documentId.toString())));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("Keine Zuordnungen");
    }

    private long countLinks(UUID tenantId, UUID caseId, UUID documentId) {
        return RequestContext.runAs(tenantId, () -> {
            Long count = jdbc.queryForObject(
                    "select count(*) from case_documents where case_id = ? and document_id = ?",
                    Long.class, caseId, documentId);
            return count == null ? 0 : count;
        });
    }
}
