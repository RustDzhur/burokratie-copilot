package de.buerokratiecopilot.documents;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import de.buerokratiecopilot.documents.domain.DocumentReview;

public interface DocumentReviewRepository extends JpaRepository<DocumentReview, UUID> {

    List<DocumentReview> findByStatusOrderByCreatedAtAsc(DocumentReview.Status status);

    List<DocumentReview> findByStatusInOrderByCreatedAtAsc(List<DocumentReview.Status> statuses);

    List<DocumentReview> findByDocumentIdOrderByCreatedAtDesc(UUID documentId);

    Optional<DocumentReview> findFirstByDocumentIdAndStatusIn(UUID documentId,
                                                               List<DocumentReview.Status> statuses);

    long countByStatus(DocumentReview.Status status);
}
