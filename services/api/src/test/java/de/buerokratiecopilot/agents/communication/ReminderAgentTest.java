package de.buerokratiecopilot.agents.communication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.deadlines.DeadlineService;
import de.buerokratiecopilot.deadlines.domain.TaskDeadline;
import de.buerokratiecopilot.tasks.TaskService;
import de.buerokratiecopilot.tasks.domain.Task;

class ReminderAgentTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    private final UUID tenantId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private TaskService taskService;
    private DeadlineService deadlineService;
    private ReminderAgent agent;

    @BeforeEach
    void setUp() {
        taskService = mock(TaskService.class);
        deadlineService = mock(DeadlineService.class);
        agent = new ReminderAgent(taskService, deadlineService);
        when(taskService.listVisible(any(UUID.class), any(UUID.class), anyBoolean(),
                isNull(), isNull(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(deadlineService.forTask(any(UUID.class))).thenReturn(List.of());
    }

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenantId, actorId, "manual", NOW, inputs);
    }

    private static TaskDeadline deadline(UUID taskId, LocalDate day) {
        TaskDeadline deadline = new TaskDeadline(UUID.randomUUID(), UUID.randomUUID(), taskId,
                null, TaskDeadline.DeadlineType.INTERNAL);
        deadline.setDueDate(day);
        return deadline;
    }

    @Test
    void createsTaskAndDeadlineForPaymentDate() {
        Task created = new Task(UUID.randomUUID(), tenantId, "Zahlung bis 2026-03-15");
        when(taskService.create(any(TaskService.CreateRequest.class), any(UUID.class)))
                .thenReturn(created);
        when(deadlineService.recordManual(any(UUID.class), isNull(), any(UUID.class),
                any(LocalDate.class), any(String.class), any(UUID.class)))
                .thenReturn(deadline(created.getId(), LocalDate.parse("2026-03-15")));

        AgentResult result = agent.run(ctx(Map.of(
                "text", "Bitte überweisen Sie den Betrag bis zum 15.03.2026.")));

        assertTrue(result.ok());
        assertEquals("1", result.meta().get("tasksCreated"));
        assertEquals("1", result.meta().get("deadlinesCreated"));
        verify(taskService, times(1)).create(any(TaskService.CreateRequest.class), any(UUID.class));
        verify(deadlineService, times(1)).recordManual(any(UUID.class), isNull(),
                any(UUID.class), any(LocalDate.class), any(String.class), any(UUID.class));
        assertTrue(result.produced().stream().anyMatch(p -> p.kind().equals("task")));
        assertTrue(result.produced().stream().anyMatch(p -> p.kind().equals("deadline")));
    }

    @Test
    void onlyProposesWhenAutoCreateIsFalse() {
        AgentResult result = agent.run(ctx(Map.of(
                "text", "Der Termin findet am 20.03.2026 statt.",
                "autoCreate", "false")));

        assertTrue(result.ok());
        assertTrue(result.produced().stream()
                .anyMatch(p -> p.kind().equals("reminder_proposal")
                        && p.ref().equals("2026-03-20")));
        verify(taskService, never()).create(any(TaskService.CreateRequest.class), any(UUID.class));
        verify(deadlineService, never()).recordManual(any(UUID.class), any(), any(UUID.class),
                any(LocalDate.class), any(String.class), any(UUID.class));
    }

    @Test
    void skipsPastDatesAndDocumentDates() {
        AgentResult result = agent.run(ctx(Map.of(
                "text", "Das Schreiben vom 10.01.2025 liegt uns vor.")));

        assertFalse(result.ok());
        verify(taskService, never()).create(any(TaskService.CreateRequest.class), any(UUID.class));
    }

    @Test
    void reusesExistingTaskAndDeadlineOnSecondRun() {
        UUID taskId = UUID.randomUUID();
        Task existing = new Task(taskId, tenantId, "Termin 2026-03-20 — Der Termin findet am 20.03.2026 statt.");
        existing.setSource(Task.Source.SYSTEM);
        existing.setDueAt(LocalDate.parse("2026-03-20").atTime(12, 0)
                .atZone(java.time.ZoneId.of("Europe/Berlin")).toInstant());
        when(taskService.listVisible(any(UUID.class), any(UUID.class), anyBoolean(),
                isNull(), isNull(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(existing)));
        when(deadlineService.forTask(taskId))
                .thenReturn(List.of(deadline(taskId, LocalDate.parse("2026-03-20"))));

        AgentResult result = agent.run(ctx(Map.of(
                "text", "Der Termin findet am 20.03.2026 statt.")));

        assertTrue(result.ok());
        assertEquals("0", result.meta().get("tasksCreated"));
        assertEquals("0", result.meta().get("deadlinesCreated"));
        verify(taskService, never()).create(any(TaskService.CreateRequest.class), any(UUID.class));
        verify(deadlineService, never()).recordManual(any(UUID.class), any(), any(UUID.class),
                any(LocalDate.class), any(String.class), any(UUID.class));
        assertTrue(result.produced().stream()
                .anyMatch(p -> p.kind().equals("existing") && p.ref().equals("2026-03-20")));
    }
}
