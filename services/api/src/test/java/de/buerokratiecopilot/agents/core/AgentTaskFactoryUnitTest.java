package de.buerokratiecopilot.agents.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import de.buerokratiecopilot.tasks.TaskService;
import de.buerokratiecopilot.tasks.domain.Task;

class AgentTaskFactoryUnitTest {

    private TaskService tasks;
    private JdbcTemplate jdbc;
    private AgentTaskFactory factory;
    private final UUID tenantId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        tasks = mock(TaskService.class);
        jdbc = mock(JdbcTemplate.class);
        factory = new AgentTaskFactory(tasks, jdbc);
    }

    @Test
    void anExistingOpenTaskIsReused() {
        UUID existing = UUID.randomUUID();
        when(jdbc.queryForList(contains("from tasks"), eq(UUID.class), any(Object.class),
                any(Object.class))).thenReturn(List.of(existing));

        AgentTaskFactory.Created result = factory.createIfAbsent(tenantId, actorId, "Titel",
                "Beschreibung", Task.Priority.NORMAL, "Grund", Instant.now(), List.of());

        assertThat(result.created()).isFalse();
        assertThat(result.taskId()).isEqualTo(existing);
        verifyNoInteractions(tasks);
    }

    @Test
    void aMissingTaskIsCreatedThroughTheTaskService() {
        UUID created = UUID.randomUUID();
        Task task = new Task(created, tenantId, "Titel");
        when(jdbc.queryForList(contains("from tasks"), eq(UUID.class), any(Object.class),
                any(Object.class))).thenReturn(List.of());
        when(tasks.create(any(), eq(actorId))).thenReturn(task);

        AgentTaskFactory.Created result = factory.createIfAbsent(tenantId, actorId, "Titel",
                "Beschreibung", Task.Priority.HIGH, "Grund", Instant.now(), List.of("support"));

        assertThat(result.created()).isTrue();
        assertThat(result.taskId()).isEqualTo(created);
        verify(tasks).create(any(), eq(actorId));
    }
}
