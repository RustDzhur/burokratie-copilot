package de.buerokratiecopilot.cases;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import de.buerokratiecopilot.cases.domain.CaseType;

public interface CaseTypeRepository extends JpaRepository<CaseType, UUID> {

    Optional<CaseType> findByCode(String code);

    List<CaseType> findByActiveTrueOrderBySortOrderAsc();
}
