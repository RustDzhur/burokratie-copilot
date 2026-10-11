package de.buerokratiecopilot.cases;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import de.buerokratiecopilot.cases.domain.Case;

public interface CaseRepository extends JpaRepository<Case, UUID> {

    Optional<Case> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);

    Optional<Case> findByTenantIdAndReference(UUID tenantId, String reference);

    /**
     * Cases a user may see: the ones they take part in.
     *
     * <p>The filter is part of the query rather than applied afterwards, so a page of results can
     * never contain a case the user is not entitled to — and an outsider cannot infer how many cases
     * exist from a page count.</p>
     */
    @Query("""
            select c from Case c
            where c.deletedAt is null
              and c.tenantId = :tenantId
              and (c.ownerUserId = :userId or c.responsibleUserId = :userId
                   or exists (select 1 from CaseParticipant p
                              where p.caseId = c.id and p.userId = :userId and p.revokedAt is null
                                and (p.expiresAt is null or p.expiresAt > current_timestamp)))
            """)
    Page<Case> findVisibleTo(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId,
                             Pageable pageable);

    @Query("""
            select c from Case c
            where c.deletedAt is null and c.tenantId = :tenantId
            """)
    Page<Case> findAllInTenant(@Param("tenantId") UUID tenantId, Pageable pageable);

    List<Case> findByStatusAndDeletedAtIsNull(Case.Status status);

    long countByTenantIdAndStatusNotIn(UUID tenantId, List<Case.Status> statuses);
}
