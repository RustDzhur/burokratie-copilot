package de.buerokratiecopilot.agents.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

import de.buerokratiecopilot.agents.core.AgentArtifactStore;
import de.buerokratiecopilot.agents.core.AgentTestFixtures;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentTaskFactory;
import de.buerokratiecopilot.cases.CaseRepository;
import de.buerokratiecopilot.cases.domain.Case;
import de.buerokratiecopilot.tasks.domain.Task;

/** {@code knowledge-meeting-memory}: only evidenced statements become decisions or tasks. */
class MeetingMemoryAgentTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");

    private CaseRepository cases;
    private AgentTaskFactory tasks;
    private AgentArtifactStore artifacts;
    private MeetingMemoryAgent agent;

    @BeforeEach
    void setUp() {
        cases = mock(CaseRepository.class);
        tasks = mock(AgentTaskFactory.class);
        artifacts = mock(AgentArtifactStore.class);
        agent = new MeetingMemoryAgent(cases, tasks, artifacts);
    }

    private AgentContext context(Map<String, String> inputs) {
        return new AgentContext(TENANT, USER, "manual", NOW, inputs);
    }

    @Test
    void recordsDecisionsAndOpenPointsAndCreatesTasksForActions() {
        Task task = new Task(UUID.randomUUID(), TENANT, "Besprechung: Angebot einholen");
        when(tasks.createIfAbsent(any(), any())).thenReturn(Optional.of(task));
        when(artifacts.put(any(), any(), anyString(), anyString(), any()))
                .thenReturn(AgentTestFixtures.artifact("meeting-memory"));
        String notes = """
                Entscheidung: Neues System einführen
                Offen: Budget für 2027
                Aufgabe: Angebot bis 2026-11-10 einholen
                """;

        AgentResult result = agent.run(context(Map.of("notes", notes,
                "participants", "Erika, Max")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("decisions", "1")
                .containsEntry("openPoints", "1")
                .containsEntry("actions", "1")
                .containsEntry("tasksCreated", "1")
                .containsEntry("participants", "2");
        assertThat(result.produced()).extracting(AgentResult.Produced::kind)
                .contains("meeting-memory", "decision", "open-point", "task");
        verify(tasks).createIfAbsent(any(), any());
    }

    @Test
    void refusesNotesThatEvidenceNothing() {
        AgentResult result = agent.run(context(Map.of("notes", "Nur Smalltalk ohne Beschluss.")));

        assertThat(result.ok()).isFalse();
        assertThat(result.meta()).containsEntry("reason", "NO_DATA");
        verify(artifacts, never()).put(any(), any(), anyString(), anyString(), any());
    }

    @Test
    void refusesWithoutNotes() {
        AgentResult result = agent.run(context(Map.of()));

        assertThat(result.ok()).isFalse();
        assertThat(result.meta()).containsEntry("reason", "NO_INPUT");
        verify(tasks, never()).createIfAbsent(any(), any());
    }

    @Test
    void parserKeepsOnlyMarkedStatementsAndReadsDates() {
        MeetingMemoryAgent.Memory memory = MeetingMemoryAgent.parse("""
                - Wir beschließen, den Vertrag zu verlängern.
                * To-do: Unterlagen prüfen
                1. Frage: Welche Frist gilt?
                Diese Zeile ist nur Kontext und keine Entscheidung.
                """);

        assertThat(memory.decisions()).containsExactly("Wir beschließen, den Vertrag zu "
                + "verlängern.");
        assertThat(memory.actions()).containsExactly("Unterlagen prüfen");
        assertThat(memory.openPoints()).containsExactly("Welche Frist gilt?");

        assertThat(MeetingMemoryAgent.dueDateIn("Angebot bis 2026-11-10 einholen"))
                .isEqualTo(LocalDate.of(2026, 11, 10));
        assertThat(MeetingMemoryAgent.dueDateIn("Angebot bis 10.11.2026 einholen"))
                .isEqualTo(LocalDate.of(2026, 11, 10));
        assertThat(MeetingMemoryAgent.dueDateIn("ohne Datum")).isNull();
    }

    @Test
    void refusesWhenTheCaseDoesNotExist() {
        UUID caseId = UUID.randomUUID();
        when(cases.findByIdAndTenantIdAndDeletedAtIsNull(caseId, TENANT))
                .thenReturn(Optional.empty());

        AgentResult result = agent.run(context(Map.of("notes", "Entscheidung: X",
                "caseId", caseId.toString())));

        assertThat(result.ok()).isFalse();
        assertThat(result.meta()).containsEntry("reason", "NOT_FOUND");
        verify(artifacts, never()).put(any(), any(), anyString(), anyString(), any());
    }

    @Test
    void linksActionsToTheCase() {
        UUID caseId = UUID.randomUUID();
        Case caseRecord = mock(Case.class);
        when(caseRecord.getReference()).thenReturn("FA-2026-1");
        when(cases.findByIdAndTenantIdAndDeletedAtIsNull(caseId, TENANT))
                .thenReturn(Optional.of(caseRecord));
        Task task = new Task(UUID.randomUUID(), TENANT, "Besprechung: X");
        when(tasks.createIfAbsent(any(), any())).thenReturn(Optional.of(task));
        when(artifacts.put(any(), any(), anyString(), anyString(), any()))
                .thenReturn(AgentTestFixtures.artifact("meeting-memory"));

        AgentResult result = agent.run(context(Map.of("notes", "Aufgabe: X",
                "caseId", caseId.toString())));

        assertThat(result.ok()).isTrue();
        verify(tasks).createIfAbsent(any(), any());
        assertThat(List.of(result.meta().get("decisions"))).isNotEmpty();
    }
}
