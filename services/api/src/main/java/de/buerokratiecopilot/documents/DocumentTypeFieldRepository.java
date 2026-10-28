package de.buerokratiecopilot.documents;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import de.buerokratiecopilot.documents.domain.DocumentTypeField;

public interface DocumentTypeFieldRepository extends JpaRepository<DocumentTypeField, UUID> {

    List<DocumentTypeField> findByDocumentTypeIdOrderBySortOrderAsc(UUID documentTypeId);
}
