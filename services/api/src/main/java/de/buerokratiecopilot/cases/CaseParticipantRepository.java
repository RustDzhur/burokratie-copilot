package de.buerokratiecopilot.cases;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import de.buerokratiecopilot.cases.domain.CaseParticipant;

public interface CaseParticipantRepository extends JpaRepository<CaseParticipant, UUID> {

    List<CaseParticipant> findByCaseIdOrderByGrantedAtAsc(UUID caseId);

    Optional<CaseParticipant> findByCaseIdAndUserId(UUID caseId, UUID userId);

    List<CaseParticipant> findByCaseIdAndRevokedAtIsNull(UUID caseId);
}
