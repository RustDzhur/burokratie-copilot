package de.buerokratiecopilot.documents;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import de.buerokratiecopilot.documents.domain.DocumentProcessingStep;

public interface DocumentProcessingStepRepository extends JpaRepository<DocumentProcessingStep, UUID> {

    List<DocumentProcessingStep> findByDocumentIdOrderByCreatedAtAsc(UUID documentId);

    Optional<DocumentProcessingStep> findByDocumentIdAndStep(UUID documentId,
                                                             DocumentProcessingStep.Step step);

    List<DocumentProcessingStep> findByStatus(DocumentProcessingStep.Status status);
}
