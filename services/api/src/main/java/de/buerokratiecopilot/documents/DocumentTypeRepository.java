package de.buerokratiecopilot.documents;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import de.buerokratiecopilot.documents.domain.DocumentType;

public interface DocumentTypeRepository extends JpaRepository<DocumentType, UUID> {

    Optional<DocumentType> findByCode(String code);

    List<DocumentType> findByActiveTrueOrderBySortOrderAsc();

    List<DocumentType> findByCategoryAndActiveTrueOrderBySortOrderAsc(String category);

    boolean existsByCode(String code);
}
