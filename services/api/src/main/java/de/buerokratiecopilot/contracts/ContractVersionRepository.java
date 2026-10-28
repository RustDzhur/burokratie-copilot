package de.buerokratiecopilot.contracts;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import de.buerokratiecopilot.contracts.domain.ContractVersion;

public interface ContractVersionRepository extends JpaRepository<ContractVersion, UUID> {

    List<ContractVersion> findByContractIdOrderByCreatedAtAsc(UUID contractId);

    Optional<ContractVersion> findByContractIdAndCurrentTrue(UUID contractId);

    Optional<ContractVersion> findByContractIdAndVersionLabel(UUID contractId, String versionLabel);
}
