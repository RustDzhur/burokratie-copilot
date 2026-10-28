package de.buerokratiecopilot.approvals;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import de.buerokratiecopilot.approvals.domain.PaymentApproval;

public interface PaymentApprovalRepository extends JpaRepository<PaymentApproval, UUID> {

    List<PaymentApproval> findByPaymentIdOrderByApprovalLevelAsc(UUID paymentId);

    List<PaymentApproval> findByApprovalIdOrderByApprovalLevelAsc(UUID approvalId);
}
