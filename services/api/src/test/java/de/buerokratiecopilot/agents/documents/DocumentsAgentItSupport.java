package de.buerokratiecopilot.agents.documents;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import de.buerokratiecopilot.documents.DocumentBlobRepository;
import de.buerokratiecopilot.documents.DocumentPageRepository;
import de.buerokratiecopilot.documents.DocumentRepository;
import de.buerokratiecopilot.documents.DocumentVersionRepository;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.documents.domain.DocumentBlob;
import de.buerokratiecopilot.documents.domain.DocumentPage;
import de.buerokratiecopilot.documents.domain.DocumentVersion;
import de.buerokratiecopilot.support.TestDataFactory;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * Fixtures for the documents-agent integration tests: real tenants, real documents with pages —
 * the agents work against the same tables the platform uses, under the same row-level security.
 */
final class DocumentsAgentItSupport {

    private DocumentsAgentItSupport() {
    }

    /** A tenant with one active user; the tenant id is returned. */
    static UUID seedTenantWithUser(TestDataFactory factory) {
        var tenant = factory.createCompany();
        var user = factory.createUser("agent-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.de");
        factory.createMembership(tenant, user);
        return tenant.getId();
    }

    /**
     * A document with one version (backed by a real blob row) and one page carrying the given text.
     * Runs in the tenant scope, so the RLS policies let the repositories through.
     */
    static UUID seedDocumentWithText(DocumentRepository documents, DocumentBlobRepository blobs,
                                     DocumentVersionRepository versions, DocumentPageRepository pages,
                                     UUID tenantId, String filename, String text,
                                     Document.ProcessingStatus status) {
        return RequestContext.runAs(tenantId, () -> {
            String hash = "f".repeat(64);
            UUID documentId = UUID.randomUUID();
            Document document = new Document(documentId, tenantId, filename, "stored/" + documentId,
                    "application/pdf", Math.max(1, text.length()), hash, Document.Source.UPLOAD);
            document.setProcessingStatus(status);
            documents.save(document);

            DocumentBlob blob = new DocumentBlob(UUID.randomUUID(), tenantId, "stored/" + documentId,
                    "local-fs", 100, hash, "application/pdf");
            blob.markClean();
            blobs.save(blob);

            DocumentVersion version = new DocumentVersion(UUID.randomUUID(), documentId, tenantId, 1,
                    blob.getId(), hash, 100, "application/pdf", filename);
            versions.save(version);

            DocumentPage page = new DocumentPage(UUID.randomUUID(), documentId, version.getId(), 1);
            page.setTextContent(text);
            pages.save(page);
            return documentId;
        });
    }

    /** A case with the given reference, inserted the way a case row really looks. */
    static UUID seedCase(JdbcTemplate jdbc, UUID tenantId, String reference, String title) {
        UUID caseId = UUID.randomUUID();
        RequestContext.runAs(tenantId, () -> jdbc.update("""
                insert into cases (id, tenant_id, reference, title, status)
                values (?, ?, ?, ?, 'NEW')
                """, caseId, tenantId, reference, title));
        return caseId;
    }
}
