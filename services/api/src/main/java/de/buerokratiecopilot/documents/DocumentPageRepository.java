package de.buerokratiecopilot.documents;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import de.buerokratiecopilot.documents.domain.DocumentPage;

public interface DocumentPageRepository extends JpaRepository<DocumentPage, UUID> {

    List<DocumentPage> findByDocumentIdOrderByPageNumberAsc(UUID documentId);

    List<DocumentPage> findByVersionIdOrderByPageNumberAsc(UUID versionId);

    void deleteByVersionId(UUID versionId);

    long countByDocumentId(UUID documentId);
}
