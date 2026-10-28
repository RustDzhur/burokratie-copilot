package de.buerokratiecopilot.documents;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import de.buerokratiecopilot.documents.domain.DocumentExtraction;

public interface DocumentExtractionRepository extends JpaRepository<DocumentExtraction, UUID> {

    List<DocumentExtraction> findByDocumentIdOrderByFieldKeyAsc(UUID documentId);

    List<DocumentExtraction> findByDocumentId(UUID documentId);

    Optional<DocumentExtraction> findByDocumentIdAndFieldKey(UUID documentId, String fieldKey);

    List<DocumentExtraction> findByVersionId(UUID versionId);

    void deleteByVersionId(UUID versionId);
}
