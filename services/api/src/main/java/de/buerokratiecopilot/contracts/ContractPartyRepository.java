package de.buerokratiecopilot.contracts;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import de.buerokratiecopilot.contracts.domain.ContractParty;

public interface ContractPartyRepository extends JpaRepository<ContractParty, UUID> {

    List<ContractParty> findByContractIdOrderByCreatedAtAsc(UUID contractId);
}
