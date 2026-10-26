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

class PhoneTranscriptAgentTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID caseId = UUID.randomUUID();
    private final UUID contactId = UUID.randomUUID();
    private JdbcTemplate jdbc;
    private CaseEventRepository caseEventRepository;
    private TaskService taskService;
    private PhoneTranscriptAgent agent;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        caseEventRepository = mock(CaseEventRepository.class);
        taskService = mock(TaskService.class);
        agent = new PhoneTranscriptAgent(jdbc, caseEventRepository, taskService);
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
    void recordsNoteWithContactAndFollowUpTasks() {
        AgentResult result = agent.run(ctx(Map.of(
                "text", "Frau Meier ruft wegen der Rechnung an. Wir klären den Betrag.",
                "contactId", contactId.toString(),
                "caller", "Frau Meier",
                "followUps", "Betrag prüfen|2026-05-05\nRückruf vereinbaren")));

        assertTrue(result.ok());
        assertEquals("2", result.meta().get("tasksCreated"));

        ArgumentCaptor<CaseEvent> captor = ArgumentCaptor.forClass(CaseEvent.class);
        verify(caseEventRepository).save(captor.capture());
        CaseEvent note = captor.getValue();
        assertEquals(CaseEvent.EventType.NOTE_ADDED, note.getEventType());
        assertEquals(contactId, note.getContactId());
        assertEquals(caseId, note.getCaseId());
        assertTrue(note.getDescription().contains("Rechnung"));
        verify(taskService, times(2)).create(any(TaskService.CreateRequest.class), any(UUID.class));
    }

    @Test
    void skipsWhenNoCaseCanBeResolved() {
        AgentResult result = agent.run(ctx(Map.of("caseId", "", "contactId", "",
                "text", "Kurzer Anruf ohne Vorgang.")));

        assertFalse(result.ok());
        assertTrue(result.summary().contains("Kein Vorgang"));
        verify(caseEventRepository, never()).save(any(CaseEvent.class));
    }

    @Test
    void reportsExistingNoteInsteadOfDuplicating() {
        UUID noteId = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), any(UUID.class), any(UUID.class), any(String.class)))
                .thenReturn(List.of(Map.of("id", noteId)));

        AgentResult result = agent.run(ctx(Map.of(
                "text", "Frau Meier ruft wegen der Rechnung an. Wir klären den Betrag.")));

        assertTrue(result.ok());
        assertEquals("true", result.meta().get("duplicate"));
        assertEquals(noteId.toString(), result.meta().get("noteId"));
        verify(caseEventRepository, never()).save(any(CaseEvent.class));
        verify(taskService, never()).create(any(TaskService.CreateRequest.class), any(UUID.class));
    }
}
