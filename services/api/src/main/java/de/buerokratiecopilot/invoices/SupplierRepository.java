package de.buerokratiecopilot.invoices;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import de.buerokratiecopilot.invoices.domain.Supplier;

public interface SupplierRepository extends JpaRepository<Supplier, UUID> {

    Optional<Supplier> findByTenantIdAndName(UUID tenantId, String name);

    Optional<Supplier> findByIdAndTenantId(UUID id, UUID tenantId);
}
