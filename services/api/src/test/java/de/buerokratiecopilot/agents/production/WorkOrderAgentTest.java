package de.buerokratiecopilot.agents.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentWorkspace;

class WorkOrderAgentTest {

    private final AgentWorkspace workspace = mock(AgentWorkspace.class);
    private final WorkOrderAgent agent = new WorkOrderAgent(new ObjectMapper(), workspace);
    private final UUID tenant = UUID.randomUUID();

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenant, UUID.randomUUID(), "manual", Instant.now(), inputs);
    }

    @Test
    void createsAWorkOrderWithStepsAndMaterials() {
        when(workspace.ensureDocument(any(), any(), any(), any(), any(), any(), anyMap(), any()))
                .thenReturn(new AgentWorkspace.DocumentRef(UUID.randomUUID(), true));
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(UUID.randomUUID(), true));
        String steps = "[{\"step\":\"Sägen\",\"minutes\":30},{\"step\":\"Montieren\",\"minutes\":60}]";
        String materials = "[{\"article\":\"Holz\",\"quantity\":2,\"unit\":\"m\"}]";

        AgentResult result = agent.run(ctx(Map.of("orderId", UUID.randomUUID().toString(),
                "productId", UUID.randomUUID().toString(), "quantity", "2",
                "dueDate", "2026-12-01", "workSteps", steps, "materials", materials)));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("steps", "2")
                .containsEntry("materials", "1")
                .containsEntry("machineStarted", "false");
    }

    @Test
    void refusesANonPositiveQuantity() {
        AgentResult result = agent.run(ctx(Map.of("orderId", "AU-1",
                "productId", UUID.randomUUID().toString(), "quantity", "0",
                "dueDate", "2026-12-01")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("quantity");
    }

    @Test
    void marksMissingStepsAsOpenPointsInsteadOfInventingThem() {
        when(workspace.ensureDocument(any(), any(), any(), any(), any(), any(), anyMap(), any()))
                .thenReturn(new AgentWorkspace.DocumentRef(UUID.randomUUID(), true));
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(UUID.randomUUID(), true));

        AgentResult result = agent.run(ctx(Map.of("orderId", UUID.randomUUID().toString(),
                "productId", UUID.randomUUID().toString(), "quantity", "5",
                "dueDate", "2026-12-01")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("openPoints", "2");
    }
}
