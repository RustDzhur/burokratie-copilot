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

class MaterialPlanAgentTest {

    private final AgentWorkspace workspace = mock(AgentWorkspace.class);
    private final MaterialPlanAgent agent = new MaterialPlanAgent(
            mock(org.springframework.jdbc.core.JdbcTemplate.class), new ObjectMapper(), workspace);
    private final UUID tenant = UUID.randomUUID();

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenant, UUID.randomUUID(), "manual", Instant.now(), inputs);
    }

    @Test
    void computesShortagesAgainstStockAndOpenOrders() {
        when(workspace.ensureDocument(any(), any(), any(), any(), any(), any(), anyMap(), any()))
                .thenReturn(new AgentWorkspace.DocumentRef(UUID.randomUUID(), true));
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(UUID.randomUUID(), true));
        String bom = "[{\"article\":\"Holz\",\"quantity\":10}]";

        AgentResult result = agent.run(ctx(Map.of("billOfMaterials", bom,
                "stock", "{\"Holz\":4}", "openOrders", "{\"Holz\":2}")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("shortages", "1")
                .containsEntry("ordered", "false");
        assertThat(result.produced()).extracting(AgentResult.Produced::kind)
                .contains("material", "task");
    }

    @Test
    void reportsNoStockInsteadOfInventingAShortage() {
        when(workspace.ensureDocument(any(), any(), any(), any(), any(), any(), anyMap(), any()))
                .thenReturn(new AgentWorkspace.DocumentRef(UUID.randomUUID(), true));
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(UUID.randomUUID(), true));
        String bom = "[{\"article\":\"Holz\",\"quantity\":10}]";

        AgentResult result = agent.run(ctx(Map.of("billOfMaterials", bom)));

        assertThat(result.ok()).isFalse();
        assertThat(result.meta()).containsEntry("reason", "NO_STOCK_DATA");
        assertThat(result.summary()).contains("ohne Bestandsdaten");
    }

    @Test
    void skipsWithoutAnyBillOfMaterials() {
        AgentResult result = agent.run(ctx(Map.of()));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("keine Stücklisten");
    }
}
