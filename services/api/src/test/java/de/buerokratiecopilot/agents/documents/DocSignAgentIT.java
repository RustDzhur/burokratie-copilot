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
 * {@code doc-sign} against the real database: a signature task is created per signable document,
 * assigned to the signer, and not created twice; duplicates are skipped with a reason.
 */
@SpringBootTest
@ActiveProfiles("test")
class DocSignAgentIT {

    @Autowired
    private DocSignAgent agent;

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

    private record Fixture(UUID tenantId, UUID userId) {
    }

    private Fixture seed() {
        UUID tenantId = DocumentsAgentItSupport.seedTenantWithUser(factory);
        UUID userId = RequestContext.runAs(tenantId, () -> jdbc.queryForObject(
                "select user_id from memberships where tenant_id = ? limit 1", UUID.class, tenantId));
        return new Fixture(tenantId, userId);
    }

    @Test
    @DisplayName("a signature task is created for the signer and a re-run does not double it")
    void preparesSignatureTaskIdempotently() {
        Fixture fixture = seed();
        UUID documentId = DocumentsAgentItSupport.seedDocumentWithText(documents, blobs, versions, pages,
                fixture.tenantId(), "Vertrag.pdf", "Vertragstext", Document.ProcessingStatus.READY);

        AgentContext context = new AgentContext(fixture.tenantId(), fixture.userId(), "manual", null,
                Map.of("documentIds", documentId.toString(),
                        "signerUserId", fixture.userId().toString(), "dueDays", "3"));
        AgentResult result = agent.run(context);

        assertThat(result.ok()).isTrue();
        assertThat(result.meta().get("prepared")).isEqualTo("1");
        assertThat(result.produced()).anySatisfy(produced ->
                assertThat(produced.kind()).isEqualTo("signature-task"));
        assertThat(signatureTasks(fixture.tenantId(), documentId)).isEqualTo(1);

        Map<String, Object> task = RequestContext.runAs(fixture.tenantId(), () -> jdbc.queryForMap("""
                select title, due_at is not null as has_due, tags
                from tasks where source_document_id = ? order by created_at limit 1
                """, documentId));
        assertThat(String.valueOf(task.get("title"))).contains("Dokument zur Unterschrift");
        assertThat((Boolean) task.get("has_due")).isTrue();

        // Idempotence: the same document is not prepared twice.
        AgentResult second = agent.run(context);
        assertThat(second.ok()).isFalse();
        assertThat(second.summary()).contains("bereits vorbereitet");
        assertThat(signatureTasks(fixture.tenantId(), documentId)).isEqualTo(1);
    }

    @Test
    @DisplayName("a duplicate document is skipped with a reason instead of being signed")
    void duplicateIsSkipped() {
        Fixture fixture = seed();
        UUID documentId = DocumentsAgentItSupport.seedDocumentWithText(documents, blobs, versions, pages,
                fixture.tenantId(), "Kopie.pdf", "Text", Document.ProcessingStatus.DUPLICATE);

        AgentResult result = agent.run(new AgentContext(fixture.tenantId(), fixture.userId(), "manual",
                null, Map.of("documentIds", documentId.toString(),
                        "signerUserId", fixture.userId().toString())));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("übersprungen");
        assertThat(signatureTasks(fixture.tenantId(), documentId)).isZero();
    }

    @Test
    @DisplayName("a signer without an active membership is refused")
    void unknownSignerFails() {
        Fixture fixture = seed();
        UUID documentId = DocumentsAgentItSupport.seedDocumentWithText(documents, blobs, versions, pages,
                fixture.tenantId(), "Vertrag.pdf", "Text", Document.ProcessingStatus.READY);

        AgentResult result = agent.run(new AgentContext(fixture.tenantId(), fixture.userId(), "manual",
                null, Map.of("documentIds", documentId.toString(),
                        "signerUserId", UUID.randomUUID().toString())));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("Unterzeichner");
        assertThat(signatureTasks(fixture.tenantId(), documentId)).isZero();
    }

    private long signatureTasks(UUID tenantId, UUID documentId) {
        Long count = RequestContext.runAs(tenantId, () -> jdbc.queryForObject("""
                select count(*) from tasks
                where source_document_id = ? and source = 'DOCUMENT' and deleted_at is null
                  and status not in ('DONE', 'CANCELLED')
                  and title like 'Dokument zur Unterschrift%'
                """, Long.class, documentId));
        return count == null ? 0 : count;
    }
}
