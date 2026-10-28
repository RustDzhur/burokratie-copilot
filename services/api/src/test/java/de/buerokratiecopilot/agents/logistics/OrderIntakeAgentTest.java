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

class OrderIntakeAgentTest {

    private final AgentWorkspace workspace = mock(AgentWorkspace.class);
    private final OrderIntakeAgent agent = new OrderIntakeAgent(mock(org.springframework.jdbc.core.JdbcTemplate.class),
            new ObjectMapper(), workspace);
    private final UUID tenant = UUID.randomUUID();

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenant, UUID.randomUUID(), "manual", Instant.now(), inputs);
    }

    @Test
    void acceptsACompleteOrderAndFilesAConfirmationTask() {
        when(workspace.ensureDocument(any(), any(), any(), any(), any(), any(), anyMap(), any()))
                .thenReturn(new AgentWorkspace.DocumentRef(UUID.randomUUID(), true));
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(UUID.randomUUID(), true));
        String items = """
                [{"article":"A-100","quantity":2,"unitPrice":49.90},
                 {"article":"B-200","quantity":1,"unitPrice":120.00}]
                """;

        AgentResult result = agent.run(ctx(Map.of("orderText", "Bestellung",
                "items", items, "deliveryDate", "2026-12-01")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("positions", "2")
                .containsEntry("total", "219.80");
        assertThat(result.summary()).contains("Bestätigung als Aufgabe");
    }

    @Test
    void returnsAnIncompletePositionForClarificationInsteadOfGuessing() {
        when(workspace.ensureDocument(any(), any(), any(), any(), any(), any(), anyMap(), any()))
                .thenReturn(new AgentWorkspace.DocumentRef(UUID.randomUUID(), true));
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(UUID.randomUUID(), true));
        String items = """
                [{"article":"A-100","quantity":0,"unitPrice":49.90}]
                """;

        AgentResult result = agent.run(ctx(Map.of("orderText", "Bestellung", "items", items)));

        assertThat(result.ok()).isFalse();
        assertThat(result.meta()).containsEntry("reason", "ORDER_INCOMPLETE");
        assertThat(result.summary()).contains("Menge nicht positiv");
    }

    @Test
    void skipsWithoutAnOrderTextOrPositions() {
        AgentResult result = agent.run(ctx(Map.of()));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("weder 'orderText' noch 'items'");
    }

    @Test
    void extractsAnOrderNumberFromTheText() {
        assertThat(OrderIntakeAgent.extractOrderNumber("Bestellung Nr. PO-2026-77"))
                .isEqualTo("PO-2026-77");
    }
}
