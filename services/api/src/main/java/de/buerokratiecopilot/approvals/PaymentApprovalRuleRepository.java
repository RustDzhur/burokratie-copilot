package de.buerokratiecopilot.approvals;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import de.buerokratiecopilot.approvals.domain.PaymentApprovalRule;

public interface PaymentApprovalRuleRepository extends JpaRepository<PaymentApprovalRule, UUID> {

    @Query("""
            select r from PaymentApprovalRule r
            where r.active = true and (r.tenantId is null or r.tenantId = :tenantId)
            """)
    List<PaymentApprovalRule> findEffective(@Param("tenantId") UUID tenantId);

    Optional<PaymentApprovalRule> findByTenantIdAndCode(UUID tenantId, String code);
}
