package de.buerokratiecopilot.audit;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import de.buerokratiecopilot.audit.domain.AuditEvent;

/**
 * Read/write access to the audit trail.
 *
 * <p>There is intentionally no delete or update method: the database rejects both, and the
 * application must not even offer the operation (spec §21.4).</p>
 */
public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    Page<AuditEvent> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);

    Page<AuditEvent> findByTenantIdAndCategoryOrderByCreatedAtDesc(UUID tenantId, AuditEvent.Category category,
                                                                 Pageable pageable);

    Page<AuditEvent> findByActorUserIdOrderByCreatedAtDesc(UUID actorUserId, Pageable pageable);

    List<AuditEvent> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(String entityType, UUID entityId);

    @Query("select e from AuditEvent e where e.correlationId = :correlationId order by e.createdAt asc")
    List<AuditEvent> findByCorrelationId(@Param("correlationId") String correlationId);

    long countByTenantIdAndCategory(UUID tenantId, AuditEvent.Category category);

    @Query("""
            select e.action, count(e) from AuditEvent e
            where e.tenantId = :tenantId
            group by e.action
            order by count(e) desc
            """)
    List<Object[]> countByAction(@Param("tenantId") UUID tenantId);
}
