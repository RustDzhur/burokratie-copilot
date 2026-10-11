package de.buerokratiecopilot.deadlines;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import de.buerokratiecopilot.deadlines.domain.DeadlineRule;

public interface DeadlineRuleRepository extends JpaRepository<DeadlineRule, UUID> {

    @Query("""
            select r from DeadlineRule r
            where r.active = true and (r.tenantId is null or r.tenantId = :tenantId)
            order by r.code
            """)
    List<DeadlineRule> findEffective(@Param("tenantId") UUID tenantId);

    @Query("""
            select r from DeadlineRule r
            where r.active = true and r.code = :code and (r.tenantId is null or r.tenantId = :tenantId)
            """)
    Optional<DeadlineRule> findEffectiveByCode(@Param("tenantId") UUID tenantId,
                                               @Param("code") String code);

    List<DeadlineRule> findByRequiresVerificationTrueAndActiveTrue();

    boolean existsByTenantIdAndCode(UUID tenantId, String code);
}
