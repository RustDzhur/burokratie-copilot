package de.buerokratiecopilot.documents;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.documents.domain.DocumentSource;

public interface DocumentSourceRepository extends JpaRepository<DocumentSource, UUID> {

    List<DocumentSource> findByDocumentId(UUID documentId);

    /** External ingest retry protection (spec §7.4): the same external object stays one document. */
    Optional<DocumentSource> findByTenantIdAndSourceTypeAndIdempotencyKey(UUID tenantId,
                                                                         Document.Source sourceType,
                                                                         String idempotencyKey);
}
