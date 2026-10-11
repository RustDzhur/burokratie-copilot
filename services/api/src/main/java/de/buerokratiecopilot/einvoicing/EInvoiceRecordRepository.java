package de.buerokratiecopilot.einvoicing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import de.buerokratiecopilot.einvoicing.domain.EInvoiceRecord;

public interface EInvoiceRecordRepository extends JpaRepository<EInvoiceRecord, UUID> {

    Optional<EInvoiceRecord> findByIdAndTenantId(UUID id, UUID tenantId);

    List<EInvoiceRecord> findByDocumentIdAndTenantId(UUID documentId, UUID tenantId);

    List<EInvoiceRecord> findByTenantIdAndValidationStatusIn(UUID tenantId,
                                                             List<EInvoiceRecord.ValidationStatus> statuses);
}
