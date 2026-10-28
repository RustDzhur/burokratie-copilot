package de.buerokratiecopilot.documents;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import de.buerokratiecopilot.documents.domain.DocumentVersion;

public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, UUID> {

    Optional<DocumentVersion> findByDocumentIdAndVersion(UUID documentId, int version);

    List<DocumentVersion> findByDocumentIdOrderByVersionDesc(UUID documentId);

    Optional<DocumentVersion> findFirstByDocumentIdOrderByVersionDesc(UUID documentId);
}
