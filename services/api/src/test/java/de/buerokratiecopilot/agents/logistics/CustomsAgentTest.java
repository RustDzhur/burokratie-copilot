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

class CustomsAgentTest {

    private final AgentWorkspace workspace = mock(AgentWorkspace.class);
    private final CustomsAgent agent = new CustomsAgent(new ObjectMapper(), workspace);
    private final UUID tenant = UUID.randomUUID();

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenant, UUID.randomUUID(), "manual", Instant.now(), inputs);
    }

    @Test
    void validatesACompleteDeclarationAndFilesTheApprovalTask() {
        when(workspace.ensureDocument(any(), any(), any(), any(), any(), any(), anyMap(), any()))
                .thenReturn(new AgentWorkspace.DocumentRef(UUID.randomUUID(), true));
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(UUID.randomUUID(), true));
        String items = """
                [{"description":"Maschinenteil","quantity":2,"value":50.00}]
                """;

        AgentResult result = agent.run(ctx(Map.of("shipmentId", UUID.randomUUID().toString(),
                "items", items, "countryOfOrigin", "DE", "incoterm", "DAP",
                "customsValue", "100.00")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("filed", "false")
                .containsEntry("itemsValue", "100.00");
        assertThat(result.summary()).contains("freigabepflichtig");
    }

    @Test
    void returnsMissingCustomsDataInsteadOfFiled() {
        when(workspace.ensureDocument(any(), any(), any(), any(), any(), any(), anyMap(), any()))
                .thenReturn(new AgentWorkspace.DocumentRef(UUID.randomUUID(), true));
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(UUID.randomUUID(), true));

        AgentResult result = agent.run(ctx(Map.of("shipmentId", UUID.randomUUID().toString(),
                "items", "[]", "countryOfOrigin", "DE", "incoterm", "UNKNOWN",
                "customsValue", "0")));

        assertThat(result.ok()).isFalse();
        assertThat(result.meta()).containsEntry("reason", "CUSTOMS_DATA_INCOMPLETE");
        assertThat(result.summary()).contains("Incoterm");
    }
}
