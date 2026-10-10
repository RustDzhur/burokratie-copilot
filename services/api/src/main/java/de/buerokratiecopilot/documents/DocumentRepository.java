package de.buerokratiecopilot.documents;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import de.buerokratiecopilot.documents.domain.Document;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    Optional<Document> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);

    /** Duplicate detection by content (spec §9.1). The tenant scope comes from the caller. */
    Optional<Document> findFirstByTenantIdAndContentHashAndDeletedAtIsNull(UUID tenantId, String contentHash);

    Page<Document> findByTenantIdAndDeletedAtIsNull(UUID tenantId, Pageable pageable);

    Page<Document> findByTenantIdAndProcessingStatusAndDeletedAtIsNull(UUID tenantId,
                                                                      Document.ProcessingStatus status,
                                                                      Pageable pageable);

    long countByTenantIdAndProcessingStatusAndDeletedAtIsNull(UUID tenantId, Document.ProcessingStatus status);

    /**
     * Search by file name or document number.
     *
     * <p>{@code query} is never {@code null}: the caller passes an empty string for "no search term".
     * A nullable string parameter makes the driver bind an untyped value, which PostgreSQL then
     * treats as {@code bytea} and {@code lower()} fails — a real bug this signature prevents.</p>
     */
    @Query("""
            select d from Document d
            where d.tenantId = :tenantId and d.deletedAt is null
              and (:status is null or d.processingStatus = :status)
              and (:query = ''
                   or lower(d.originalFilename) like lower(concat('%', :query, '%'))
                   or lower(d.documentNumber) like lower(concat('%', :query, '%')))
            """)
    Page<Document> search(@Param("tenantId") UUID tenantId,
                          @Param("query") String query,
                          @Param("status") Document.ProcessingStatus status,
                          Pageable pageable);

    List<Document> findByDuplicateOfDocumentId(UUID duplicateOfDocumentId);
}
