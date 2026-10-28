package de.buerokratiecopilot.invoices;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import de.buerokratiecopilot.invoices.domain.Invoice;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    Optional<Invoice> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);

    /** The same supplier and number means the same invoice; a second copy is a duplicate. */
    Optional<Invoice> findByTenantIdAndSupplierIdAndInvoiceNumber(UUID tenantId, UUID supplierId,
                                                                  String invoiceNumber);

    Page<Invoice> findByTenantIdAndDeletedAtIsNull(UUID tenantId, Pageable pageable);

    @org.springframework.data.jpa.repository.Query("""
            select i from Invoice i
            where i.tenantId = :tenantId and i.deletedAt is null
              and i.status in :statuses and i.direction in :directions
            """)
    Page<Invoice> search(@org.springframework.data.repository.query.Param("tenantId") UUID tenantId,
                         @org.springframework.data.repository.query.Param("statuses")
                         java.util.Collection<Invoice.Status> statuses,
                         @org.springframework.data.repository.query.Param("directions")
                         java.util.Collection<Invoice.Direction> directions,
                         Pageable pageable);

    Page<Invoice> findByTenantIdAndDirectionAndDeletedAtIsNull(UUID tenantId, Invoice.Direction direction,
                                                               Pageable pageable);

    /**
     * Invoices whose validation ended in the given state.
     *
     * <p>A native query, because the state lives inside a jsonb document and a derived query cannot
     * look into it. The value is read with {@code ->>}, which returns text, and compared with the
     * state the validator wrote.</p>
     */
    @Query(value = """
            select * from invoices
            where tenant_id = :tenantId and deleted_at is null
              and validation_state ->> 'state' = :state
            order by issue_date desc nulls last, created_at desc
            """, nativeQuery = true)
    List<Invoice> findByValidationState(@Param("tenantId") UUID tenantId, @Param("state") String state);
}
