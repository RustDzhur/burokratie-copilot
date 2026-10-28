package de.buerokratiecopilot.documents;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import de.buerokratiecopilot.documents.domain.DocumentClassification;

public interface DocumentClassificationRepository extends JpaRepository<DocumentClassification, UUID> {

    Optional<DocumentClassification> findByDocumentIdAndCurrentTrue(UUID documentId);

    List<DocumentClassification> findByDocumentIdOrderByCreatedAtDesc(UUID documentId);

    /** Only one decision is current at a time; the partial unique index enforces it. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update DocumentClassification c set c.current = false where c.documentId = :documentId and c.current = true")
    int clearCurrent(@Param("documentId") UUID documentId);

    long countByMethod(String method);
}
