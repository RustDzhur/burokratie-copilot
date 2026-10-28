package de.buerokratiecopilot.approvals;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import de.buerokratiecopilot.approvals.domain.Approval;

public interface ApprovalRepository extends JpaRepository<Approval, UUID> {

    Optional<Approval> findByIdAndTenantId(UUID id, UUID tenantId);

    List<Approval> findByTenantIdAndStatusOrderByCreatedAtAsc(UUID tenantId, Approval.Status status);

    List<Approval> findByTenantIdAndSubjectTypeAndSubjectId(UUID tenantId, String subjectType,
                                                           UUID subjectId);
}
