package de.buerokratiecopilot.documents;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import de.buerokratiecopilot.documents.domain.DocumentBlob;

public interface DocumentBlobRepository extends JpaRepository<DocumentBlob, UUID> {

    List<DocumentBlob> findByTenantIdAndScanStatus(UUID tenantId, DocumentBlob.ScanStatus scanStatus);

    List<DocumentBlob> findByTenantIdAndDeletedAtIsNull(UUID tenantId);
}
