package de.buerokratiecopilot.invoices.outgoing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvoiceNumberSequenceRepository extends JpaRepository<InvoiceNumberSequence, UUID> {

    /**
     * Reads the sequence and locks it for the rest of the transaction.
     *
     * <p>The lock is what makes two simultaneous invoices get different numbers. Reading without it and
     * writing later would let both see the same value — which is the duplicate the acceptance criterion
     * forbids.</p>
     */
    @Query(value = "select * from invoice_number_sequences where tenant_id = :tenantId and code = :code "
            + "for update", nativeQuery = true)
    Optional<InvoiceNumberSequence> lockByTenantAndCode(@Param("tenantId") UUID tenantId,
                                                       @Param("code") String code);

    List<InvoiceNumberSequence> findByTenantIdAndActiveTrueOrderByCode(UUID tenantId);

    Optional<InvoiceNumberSequence> findByTenantIdAndCode(UUID tenantId, String code);
}
