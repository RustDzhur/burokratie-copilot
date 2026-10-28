package de.buerokratiecopilot.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import de.buerokratiecopilot.identity.domain.Membership;

public interface MembershipRepository extends JpaRepository<Membership, UUID> {

    Optional<Membership> findByTenantIdAndUserId(UUID tenantId, UUID userId);

    List<Membership> findByUserIdAndStatus(UUID userId, Membership.Status status);

    List<Membership> findByTenantId(UUID tenantId);

    @Query("""
            select m from Membership m
            where m.tenantId = :tenantId and m.status = 'ACTIVE'
            """)
    List<Membership> findActiveByTenant(@Param("tenantId") UUID tenantId);

    long countByTenantIdAndStatus(UUID tenantId, Membership.Status status);
}
