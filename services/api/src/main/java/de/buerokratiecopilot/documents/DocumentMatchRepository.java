package de.buerokratiecopilot.documents;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import de.buerokratiecopilot.documents.domain.DocumentMatch;

public interface DocumentMatchRepository extends JpaRepository<DocumentMatch, UUID> {

    List<DocumentMatch> findByDocumentIdOrderByScoreDesc(UUID documentId);

    List<DocumentMatch> findByDocumentId(UUID documentId);

    List<DocumentMatch> findByStatusOrderByScoreDesc(DocumentMatch.Status status);

    Optional<DocumentMatch> findByDocumentIdAndMatchTypeAndTargetId(UUID documentId,
                                                                    DocumentMatch.MatchType matchType,
                                                                    UUID targetId);

    List<DocumentMatch> findByDocumentIdAndMatchType(UUID documentId, DocumentMatch.MatchType matchType);

    void deleteByDocumentId(UUID documentId);

    long countByStatus(DocumentMatch.Status status);
}
