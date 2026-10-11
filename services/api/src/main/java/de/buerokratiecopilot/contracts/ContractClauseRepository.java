package de.buerokratiecopilot.contracts;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import de.buerokratiecopilot.contracts.domain.ContractClause;

public interface ContractClauseRepository extends JpaRepository<ContractClause, UUID> {

    List<ContractClause> findByContractIdOrderBySortOrderAsc(UUID contractId);

    List<ContractClause> findByContractIdAndCriticalTrueOrderBySortOrderAsc(UUID contractId);

    List<ContractClause> findByVersionIdOrderBySortOrderAsc(UUID versionId);
}
