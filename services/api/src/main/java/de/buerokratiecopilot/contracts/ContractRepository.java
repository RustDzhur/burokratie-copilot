package de.buerokratiecopilot.contracts;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import de.buerokratiecopilot.contracts.domain.Contract;

public interface ContractRepository extends JpaRepository<Contract, UUID> {

    Optional<Contract> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, UUID tenantId);

    Optional<Contract> findByTenantIdAndContractNumber(UUID tenantId, String contractNumber);

    Page<Contract> findByTenantIdAndDeletedAtIsNull(UUID tenantId, Pageable pageable);

    List<Contract> findByTenantIdAndStatusAndDeletedAtIsNull(UUID tenantId, Contract.Status status);

    /** Contracts whose term ends in the given window, for the renewal overview. */
    List<Contract> findByTenantIdAndEndDateBetweenAndDeletedAtIsNull(UUID tenantId,
                                                                    java.time.LocalDate from,
                                                                    java.time.LocalDate to);
}
