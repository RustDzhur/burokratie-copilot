package de.buerokratiecopilot.agents.legal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import de.buerokratiecopilot.agents.core.AgentArtifactStore;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentTaskFactory;
import de.buerokratiecopilot.agents.core.AgentTestFixtures;
import de.buerokratiecopilot.cases.CaseDocumentRepository;
import de.buerokratiecopilot.cases.CaseEventRepository;
import de.buerokratiecopilot.cases.CaseParticipantRepository;
import de.buerokratiecopilot.cases.CaseRepository;
import de.buerokratiecopilot.cases.domain.Case;
import de.buerokratiecopilot.cases.domain.CaseEvent;
import de.buerokratiecopilot.cases.domain.CaseParticipant;
import de.buerokratiecopilot.tasks.domain.Task;

/** {@code legal-litigation-file}: dossier with chronology, deadlines, gaps and tasks. */
class LegalLitigationFileAgentTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 26);

    private CaseRepository cases;
    private CaseDocumentRepository caseDocuments;
    private CaseEventRepository caseEvents;
    private CaseParticipantRepository participants;
    private JdbcTemplate jdbc;
    private AgentTaskFactory tasks;
    private AgentArtifactStore artifacts;
    private LegalLitigationFileAgent agent;

    @BeforeEach
    void setUp() {
        cases = mock(CaseRepository.class);
        caseDocuments = mock(CaseDocumentRepository.class);
        caseEvents = mock(CaseEventRepository.class);
        participants = mock(CaseParticipantRepository.class);
        jdbc = mock(JdbcTemplate.class);
        tasks = mock(AgentTaskFactory.class);
        artifacts = mock(AgentArtifactStore.class);
        agent = new LegalLitigationFileAgent(cases, caseDocuments, caseEvents, participants, jdbc,
                tasks, artifacts);
    }

    private AgentContext context(Map<String, String> inputs) {
        return new AgentContext(TENANT, USER, "manual", NOW, inputs);
    }

    @Test
    void buildsTheFileWithChronologyUrgentDeadlineAndGaps() {
        UUID caseId = UUID.randomUUID();
        Case caseRecord = mock(Case.class);
        when(caseRecord.getId()).thenReturn(caseId);
        when(caseRecord.getReference()).thenReturn("ST-2026-1");
        when(caseRecord.getTitle()).thenReturn("Streit um Rechnung");
        when(caseRecord.getStatus()).thenReturn(Case.Status.NEW);
        when(caseRecord.getOpenedAt()).thenReturn(NOW);
        when(cases.findByIdAndTenantIdAndDeletedAtIsNull(caseId, TENANT))
                .thenReturn(Optional.of(caseRecord));
        when(caseDocuments.findByCaseId(caseId)).thenReturn(List.of(
                new CaseDocumentRepository.LinkedDocument(UUID.randomUUID(), "klage.pdf", "K-1",
                        "READY", true, "MANUAL", 1.0),
                new CaseDocumentRepository.LinkedDocument(UUID.randomUUID(), "rechnung.pdf", "R-1",
                        "FAILED", false, "MANUAL", 1.0)));
        CaseEvent event = mock(CaseEvent.class);
        when(event.getOccurredAt()).thenReturn(NOW);
        when(event.getEventType()).thenReturn(CaseEvent.EventType.CORRESPONDENCE_RECEIVED);
        when(event.getTitle()).thenReturn("Mahnung erhalten");
        when(caseEvents.findByCaseIdOrderByOccurredAtDesc(caseId)).thenReturn(List.of(event));
        CaseParticipant participant = mock(CaseParticipant.class);
        when(participant.getUserId()).thenReturn(USER);
        when(participant.getRole()).thenReturn(CaseParticipant.Role.OWNER);
        when(participant.getAccessLevel()).thenReturn(CaseParticipant.AccessLevel.FULL);
        when(participant.getGrantedAt()).thenReturn(NOW);
        when(participants.findByCaseIdOrderByGrantedAtAsc(caseId))
                .thenReturn(List.of(participant));
        doReturn(List.of(new LegalLitigationFileAgent.Deadline(UUID.randomUUID(), "Frist",
                "INTERNAL", TODAY.plusDays(5), 5, "NEW")))
                .when(jdbc).query(anyString(), any(RowMapper.class), any(), any());
        when(jdbc.queryForList(contains("from tasks"), any(Object[].class)))
                .thenReturn(List.of());
        when(tasks.createIfAbsent(any(), any()))
                .thenReturn(Optional.of(AgentTestFixtures.task(TENANT, "Aufgabe")));
        when(artifacts.put(any(), any(), anyString(), anyString(), any()))
                .thenReturn(AgentTestFixtures.artifact("litigation-file"));

        AgentResult result = agent.run(context(Map.of("caseId", caseId.toString(),
                "disputeAmount", "2500,00", "jurisdiction", "Berlin")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("documents", "2")
                .containsEntry("events", "1")
                .containsEntry("deadlines", "1")
                .containsEntry("urgentDeadlines", "1")
                .containsEntry("requiresLegalReview", "true");
        assertThat(result.produced()).extracting(AgentResult.Produced::kind)
                .contains("litigation-file", "deadline", "task");
        verify(tasks, org.mockito.Mockito.atLeastOnce()).createIfAbsent(any(), any());
        assertThat(result.summary()).contains("ersetzt keine anwaltliche Bewertung");
    }

    @Test
    void refusesAnUnknownCase() {
        UUID caseId = UUID.randomUUID();
        when(cases.findByIdAndTenantIdAndDeletedAtIsNull(caseId, TENANT))
                .thenReturn(Optional.empty());

        AgentResult result = agent.run(context(Map.of("caseId", caseId.toString())));

        assertThat(result.ok()).isFalse();
        assertThat(result.meta()).containsEntry("reason", "NOT_FOUND");
        verify(artifacts, never()).put(any(), any(), anyString(), anyString(), any());
    }

    @Test
    void refusesWithoutACase() {
        AgentResult result = agent.run(context(Map.of()));

        assertThat(result.ok()).isFalse();
        assertThat(result.meta()).containsEntry("reason", "NO_INPUT");
        verify(tasks, never()).createIfAbsent(any(), any());
    }
}
