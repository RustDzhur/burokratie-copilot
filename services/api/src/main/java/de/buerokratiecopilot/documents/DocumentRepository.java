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
     * treats as {@code bytea} and {@code lower()} fails — a real bug this signature prevents. For the same
     * reason the status and source filters are never empty: "no filter" is passed as every value.</p>
     */
    @Query("""
            select d from Document d
            where d.tenantId = :tenantId and d.deletedAt is null
              and d.processingStatus in :statuses
              and d.source in :sources
              and (:category = ''
                   or (:category = 'UNSORTED' and d.documentTypeId is null)
                   or (:category <> 'UNSORTED' and d.documentTypeId in
                        (select t.id from DocumentType t where t.category = :category)))
              and (:partyId = '' or (:partyId = 'NONE' and d.partyId is null) or cast(d.partyId as string) = :partyId)
              and (:typeCode = ''
                   or d.documentTypeId in (select t.id from DocumentType t where t.code = :typeCode))
              and (:query = ''
                   or lower(d.originalFilename) like lower(concat('%', :query, '%'))
                   or lower(d.documentNumber) like lower(concat('%', :query, '%')))
            """)
    Page<Document> search(@Param("tenantId") UUID tenantId,
                          @Param("query") String query,
                          @Param("statuses") java.util.Collection<Document.ProcessingStatus> statuses,
                          @Param("sources") java.util.Collection<Document.Source> sources,
                          @Param("category") String category,
                          @Param("typeCode") String typeCode,
                          @Param("partyId") String partyId,
                          Pageable pageable);

    /** How many documents lie in each category; documents without a confirmed type are counted as UNSORTED. */
    @Query("""
            select coalesce(t.category, 'UNSORTED'), count(d) from Document d
            left join DocumentType t on t.id = d.documentTypeId
            where d.tenantId = :tenantId and d.deletedAt is null
              and d.processingStatus <> de.buerokratiecopilot.documents.domain.Document$ProcessingStatus.DUPLICATE
            group by coalesce(t.category, 'UNSORTED')
            """)
    List<Object[]> countByCategory(@Param("tenantId") UUID tenantId);

    List<Document> findByDuplicateOfDocumentId(UUID duplicateOfDocumentId);
}
