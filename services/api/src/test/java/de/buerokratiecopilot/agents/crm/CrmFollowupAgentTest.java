package de.buerokratiecopilot.agents.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.notifications.NotificationRepository;
import de.buerokratiecopilot.notifications.NotificationService;
import de.buerokratiecopilot.tasks.TaskRepository;
import de.buerokratiecopilot.tasks.TaskService;
import de.buerokratiecopilot.tasks.domain.Task;

class CrmFollowupAgentTest {

    private final CrmStore store = mock(CrmStore.class);
    private final TaskService taskService = mock(TaskService.class);
    private final TaskRepository taskRepository = mock(TaskRepository.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final NotificationRepository notificationRepository = mock(NotificationRepository.class);
    private final CrmFollowupAgent agent = new CrmFollowupAgent(store, taskService, taskRepository,
            notificationService, notificationRepository);

    @Test
    void plansATaskAndNotifiesTheAssignee() {
        UUID tenant = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(store.findOpenFollowupTask(any(), any())).thenReturn(Optional.empty());
        Task created = new Task(taskId, tenant, "Follow-up: Kontakt " + contactId);
        when(taskService.create(any(), eq(actor))).thenReturn(created);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(created));
        when(notificationRepository.findByUserIdAndDedupKey(any(), any()))
                .thenReturn(Optional.empty());

        AgentResult result = agent.run(new AgentContext(tenant, actor, "manual", Instant.now(),
                Map.of("contactId", contactId.toString(), "rule", "default",
                        "note", "Nachfassen nach dem Erstgespräch")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("created", "true")
                .containsEntry("notified", "true").containsKey("taskId");
        assertThat(result.produced()).extracting(p -> p.kind()).contains("task");
        verify(notificationService).notify(any());

        ArgumentCaptor<Task> captor = ArgumentCaptor.forClass(Task.class);
        verify(taskRepository).save(captor.capture());
        assertThat(captor.getValue().getMetadata()).containsEntry("agent", "crm-followup")
                .containsKey("followupKey");
    }

    @Test
    void doesNotPlanTheSameFollowupTwice() {
        UUID tenant = UUID.randomUUID();
        UUID existingTaskId = UUID.randomUUID();
        when(store.findOpenFollowupTask(any(), any())).thenReturn(Optional.of(existingTaskId));

        AgentResult result = agent.run(new AgentContext(tenant, UUID.randomUUID(), "manual",
                Instant.now(), Map.of("contactId", UUID.randomUUID().toString())));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("existing", "true");
        verify(taskService, never()).create(any(), any());
        verify(notificationService, never()).notify(any());
    }

    @Test
    void leadNewSkipsWhenTheLeadLeftStageNew() {
        UUID tenant = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", contactId);
        row.put("metadata", Map.of("lead", Map.of("stage", "QUALIFIED")));
        when(store.findContactRow(tenant, contactId)).thenReturn(Optional.of(row));

        AgentResult result = agent.run(new AgentContext(tenant, UUID.randomUUID(), "manual",
                Instant.now(), Map.of("contactId", contactId.toString(), "rule", "lead_new")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("nicht in Stufe NEW");
        verify(taskService, never()).create(any(), any());
    }

    @Test
    void stageBasedPlansWithTheStageInterval() {
        UUID tenant = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-26T10:00:00Z");
        when(store.dealInfo(tenant, caseId))
                .thenReturn(Optional.of(new CrmStore.DealInfo("PROPOSAL", null)));
        when(store.findOpenFollowupTask(any(), any())).thenReturn(Optional.empty());
        Task created = new Task(taskId, tenant, "Follow-up: Vorgang " + caseId);
        when(taskService.create(any(), any())).thenReturn(created);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(created));

        AgentResult result = agent.run(new AgentContext(tenant, UUID.randomUUID(), "manual", now,
                Map.of("caseId", caseId.toString(), "rule", "stage_based")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("rule", "stage_based");
        ArgumentCaptor<TaskService.CreateRequest> captor =
                ArgumentCaptor.forClass(TaskService.CreateRequest.class);
        verify(taskService).create(captor.capture(), any());
        long days = java.time.Duration.between(now, captor.getValue().dueAt()).toDays();
        assertThat(days).isEqualTo(7);
    }
}
