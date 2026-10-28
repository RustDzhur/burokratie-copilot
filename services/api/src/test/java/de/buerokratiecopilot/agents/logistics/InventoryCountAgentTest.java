package de.buerokratiecopilot.agents.logistics;

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

class InventoryCountAgentTest {

    private final AgentWorkspace workspace = mock(AgentWorkspace.class);
    private final InventoryCountAgent agent = new InventoryCountAgent(new ObjectMapper(), workspace);
    private final UUID tenant = UUID.randomUUID();

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenant, UUID.randomUUID(), "manual", Instant.now(), inputs);
    }

    @Test
    void preparesCountingListsAndASecondCountTask() {
        when(workspace.ensureDocument(any(), any(), any(), any(), any(), any(), anyMap(), any()))
                .thenReturn(new AgentWorkspace.DocumentRef(UUID.randomUUID(), true));
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(UUID.randomUUID(), true));
        String groups = """
                [{"name":"Regal A","location":"Halle 1"},{"name":"Regal B"}]
                """;

        AgentResult result = agent.run(ctx(Map.of("warehouseId", UUID.randomUUID().toString(),
                "countDate", "2026-12-01", "itemGroups", groups)));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("groups", "2")
                .containsEntry("tasksCreated", "4")
                .containsEntry("posted", "false");
    }

    @Test
    void skipsWithoutCountingGroups() {
        AgentResult result = agent.run(ctx(Map.of("warehouseId", UUID.randomUUID().toString(),
                "countDate", "2026-12-01")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("keine Zählgruppen");
    }
}
