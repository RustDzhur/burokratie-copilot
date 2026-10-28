package de.buerokratiecopilot.agents.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentWorkspace;

class SchedulingAgentTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AgentWorkspace workspace = mock(AgentWorkspace.class);
    private final SchedulingAgent agent = new SchedulingAgent(jdbc, new ObjectMapper(), workspace);
    private final UUID tenant = UUID.randomUUID();

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenant, UUID.randomUUID(), "manual", Instant.now(), inputs);
    }

    @Test
    void schedulesSuppliedWorkOrdersWithinTheHorizon() {
        when(workspace.ensureDocument(any(), any(), any(), any(), any(), any(), anyMap(), any()))
                .thenReturn(new AgentWorkspace.DocumentRef(UUID.randomUUID(), true));
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(UUID.randomUUID(), true));
        String orders = """
                [{"orderId":"WO-1","dueDate":"2026-11-10","workSteps":[{"minutes":120}]},
                 {"orderId":"WO-2","dueDate":"2026-11-12","workSteps":[{"minutes":60}]}]
                """;

        AgentResult result = agent.run(ctx(Map.of("workOrders", orders)));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("orders", "2")
                .containsEntry("overloaded", "0")
                .containsEntry("machineStarted", "false");
    }

    @Test
    void skipsWithoutAnyWorkOrder() {
        when(jdbc.queryForList(contains("production-work-order"), any(Object[].class)))
                .thenReturn(List.of());

        AgentResult result = agent.run(ctx(Map.of()));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("keine offenen Fertigungsaufträge");
    }

    @Test
    void detectsAnOverloadBeyondTheDueDate() {
        SchedulingAgent.Schedule schedule = SchedulingAgent.schedule(List.of(
                        new SchedulingAgent.WorkOrder("WO-1", java.time.LocalDate.of(2026, 11, 2),
                                20, false)),
                List.of(new SchedulingAgent.Capacity("Linie 1", 8)),
                java.time.LocalDate.of(2026, 11, 2), java.time.LocalDate.of(2026, 11, 6));

        assertThat(schedule.overloaded()).hasSize(1);
        assertThat(schedule.rows().getFirst().conflict()).isTrue();
    }
}
