package de.buerokratiecopilot.cases;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import de.buerokratiecopilot.cases.domain.CaseEvent;

public interface CaseEventRepository extends JpaRepository<CaseEvent, UUID> {

    List<CaseEvent> findByCaseIdOrderByOccurredAtDesc(UUID caseId);

    List<CaseEvent> findTop20ByCaseIdOrderByOccurredAtDesc(UUID caseId);
}
