package de.buerokratiecopilot.agents.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentWorkspace;

class WarrantyAgentTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AgentWorkspace workspace = mock(AgentWorkspace.class);
    private final WarrantyAgent agent = new WarrantyAgent(jdbc, workspace);
    private final UUID tenant = UUID.randomUUID();

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenant, UUID.randomUUID(), "manual", Instant.now(), inputs);
    }

    @Test
    void separatesWarrantyFromGoodwillAndOrdersNoRepair() {
        when(jdbc.queryForList(contains("from invoices"), any(Object.class), any(Object.class))).thenReturn(List.of());
        when(jdbc.queryForList(contains("from purchase_orders"), any(Object.class), any(Object.class))).thenReturn(List.of());
        when(workspace.ensureDocument(any(), any(), any(), any(), any(), any(), anyMap(), any()))
                .thenReturn(new AgentWorkspace.DocumentRef(UUID.randomUUID(), true));
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(UUID.randomUUID(), true));

        AgentResult result = agent.run(ctx(Map.of("orderId", "AU-1",
                "purchaseDate", "2025-06-01", "defectDescription", "Getriebe defekt")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("status", "IN_WARRANTY")
                .containsEntry("repairOrdered", "false");
        assertThat(result.summary()).contains("keine Reparatur beauftragt");
    }

    @Test
    void skipsWithoutPurchaseDateOrDefect() {
        AgentResult result = agent.run(ctx(Map.of("orderId", "AU-1")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("purchaseDate");
    }

    @Test
    void movesToGoodwillSixMonthsAfterTheWarranty() {
        assertThat(WarrantyAgent.status(LocalDate.of(2023, 1, 1),
                LocalDate.of(2025, 6, 1), 24)).isEqualTo(WarrantyAgent.Status.GOODWILL);
        assertThat(WarrantyAgent.status(LocalDate.of(2020, 1, 1),
                LocalDate.of(2026, 1, 1), 24)).isEqualTo(WarrantyAgent.Status.EXPIRED);
    }
}
