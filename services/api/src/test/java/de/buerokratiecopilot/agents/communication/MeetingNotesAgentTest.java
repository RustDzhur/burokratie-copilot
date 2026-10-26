package de.buerokratiecopilot.agents.communication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.cases.CaseEventRepository;
import de.buerokratiecopilot.cases.domain.CaseEvent;
import de.buerokratiecopilot.tasks.TaskService;
import de.buerokratiecopilot.tasks.domain.Task;

class MeetingNotesAgentTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID caseId = UUID.randomUUID();
    private final UUID contactA = UUID.randomUUID();
    private final UUID contactB = UUID.randomUUID();
    private JdbcTemplate jdbc;
    private CaseEventRepository caseEventRepository;
    private TaskService taskService;
    private MeetingNotesAgent agent;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        caseEventRepository = mock(CaseEventRepository.class);
        taskService = mock(TaskService.class);
        agent = new MeetingNotesAgent(jdbc, caseEventRepository, taskService);
        when(jdbc.queryForList(anyString(), any(UUID.class), any(UUID.class)))
                .thenReturn(List.of(Map.of("id", caseId)));
        when(jdbc.queryForList(anyString(), any(UUID.class), any(UUID.class), any(String.class)))
                .thenReturn(List.of());
        when(caseEventRepository.save(any(CaseEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(taskService.create(any(TaskService.CreateRequest.class), any(UUID.class)))
                .thenAnswer(invocation -> new Task(UUID.randomUUID(), tenantId,
                        invocation.getArgument(0, TaskService.CreateRequest.class).title()));
    }

    private AgentContext ctx(Map<String, String> inputs) {
        Map<String, String> all = new java.util.HashMap<>(inputs);
        all.putIfAbsent("caseId", caseId.toString());
        return new AgentContext(tenantId, UUID.randomUUID(), "manual", null, all);
    }

    @Test
    void recordsNotePerParticipantAndCreatesTasks() {
        AgentResult result = agent.run(ctx(Map.of(
                "title", "Jahresgespräch",
                "participants", contactA + "," + contactB,
                "minutes", "Wir haben den Vertrag besprochen.",
                "decisions", "Vertrag verlängern",
                "actionItems", "Angebot vorbereiten|2026-06-01\nTermin bestätigen")));

        assertTrue(result.ok());
        assertEquals("2", result.meta().get("notesCreated"));
        assertEquals("2", result.meta().get("tasksCreated"));

        ArgumentCaptor<CaseEvent> captor = ArgumentCaptor.forClass(CaseEvent.class);
        verify(caseEventRepository, times(2)).save(captor.capture());
        List<CaseEvent> notes = captor.getAllValues();
        assertEquals(2, notes.size());
        assertTrue(notes.stream().anyMatch(n -> contactA.equals(n.getContactId())));
        assertTrue(notes.stream().anyMatch(n -> contactB.equals(n.getContactId())));
        assertTrue(notes.stream().allMatch(n -> n.getTitle().contains("Jahresgespräch")));
        assertTrue(notes.stream().allMatch(n -> n.getMetadata().containsKey("decisions")));
        verify(taskService, times(2)).create(any(TaskService.CreateRequest.class), any(UUID.class));
    }

    @Test
    void skipsWhenTitleIsMissing() {
        AgentResult result = agent.run(ctx(Map.of()));

        assertFalse(result.ok());
        assertTrue(result.summary().contains("'title' fehlt"));
        verify(caseEventRepository, never()).save(any(CaseEvent.class));
    }

    @Test
    void reportsExistingNoteInsteadOfDuplicating() {
        UUID noteId = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), any(UUID.class), any(UUID.class), any(String.class)))
                .thenReturn(List.of(Map.of("id", noteId)));

        AgentResult result = agent.run(ctx(Map.of(
                "title", "Jahresgespräch",
                "date", "2026-03-01")));

        assertTrue(result.ok());
        assertEquals("true", result.meta().get("duplicate"));
        verify(caseEventRepository, never()).save(any(CaseEvent.class));
        verify(taskService, never()).create(any(TaskService.CreateRequest.class), any(UUID.class));
    }
}
