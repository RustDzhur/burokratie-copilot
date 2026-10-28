package de.buerokratiecopilot.approvals;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import de.buerokratiecopilot.approvals.domain.Payment;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);

    /** The key that makes a repeated confirmation harmless rather than a second payment. */
    Optional<Payment> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);

    List<Payment> findByTenantIdAndInvoiceId(UUID tenantId, UUID invoiceId);

    List<Payment> findByTenantIdAndStatus(UUID tenantId, Payment.Status status);
}
