package de.buerokratiecopilot.agents.ecommerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentWorkspace;

class EcommerceOrderSyncAgentTest {

    private final AgentWorkspace workspace = mock(AgentWorkspace.class);
    private final EcommerceOrderSyncAgent agent = new EcommerceOrderSyncAgent(new ObjectMapper(), workspace);
    private final UUID tenant = UUID.randomUUID();

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenant, UUID.randomUUID(), "manual", Instant.now(), inputs);
    }

    @Test
    void createsAFulfillmentTaskForACompleteOrder() {
        UUID taskId = UUID.randomUUID();
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(taskId, true));
        when(workspace.ensureDocument(any(), any(), any(), any(), any(), any(), anyMap(), any()))
                .thenReturn(new AgentWorkspace.DocumentRef(UUID.randomUUID(), true));
        String orders = """
                [{"externalNumber":"S-1001","customerName":"Marta","paymentStatus":"PAID",
                  "deliveryAddress":{"street":"Hauptstr. 1","postalCode":"10115","city":"Berlin"},
                  "items":[{"sku":"A-1","quantity":2}],"total":59.90}]
                """;

        AgentResult result = agent.run(ctx(Map.of("channel", "SHOP", "orders", orders)));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("fulfillmentTasksCreated", "1");
        assertThat(result.produced()).extracting(AgentResult.Produced::kind)
                .contains("task", "generated_document");
    }

    @Test
    void returnsAnIncompleteOrderForClarificationInsteadOfFulfillingIt() {
        UUID taskId = UUID.randomUUID();
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(taskId, true));
        when(workspace.ensureDocument(any(), any(), any(), any(), any(), any(), anyMap(), any()))
                .thenReturn(new AgentWorkspace.DocumentRef(UUID.randomUUID(), true));
        String orders = """
                [{"externalNumber":"S-1002","paymentStatus":"PAID","items":[]}]
                """;

        AgentResult result = agent.run(ctx(Map.of("orders", orders)));

        assertThat(result.ok()).isFalse();
        assertThat(result.meta()).containsEntry("unclear", "1");
        assertThat(result.summary()).contains("keine Bestellung war vollständig");
    }

    @Test
    void skipsHonestlyWithoutAnOrderBatch() {
        AgentResult result = agent.run(ctx(Map.of("channel", "SHOP")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("keine Bestelldaten");
        verify(workspace, never()).ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap());
    }
}
