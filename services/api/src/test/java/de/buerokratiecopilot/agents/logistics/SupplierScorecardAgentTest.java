package de.buerokratiecopilot.agents.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentWorkspace;

class SupplierScorecardAgentTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AgentWorkspace workspace = mock(AgentWorkspace.class);
    private final SupplierScorecardAgent agent = new SupplierScorecardAgent(jdbc, new ObjectMapper(),
            workspace);
    private final UUID tenant = UUID.randomUUID();

    private AgentContext ctx() {
        return new AgentContext(tenant, UUID.randomUUID(), "manual",
                Instant.parse("2026-11-01T10:00:00Z"),
                Map.of("periodFrom", "2026-01-01", "periodTo", "2026-06-30"));
    }

    @Test
    void scoresOnlyTheMeasurableRatesAndFilesAReviewTask() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", UUID.randomUUID());
        row.put("name", "Beispiel Lieferant GmbH");
        row.put("order_count", 10);
        row.put("overdue_orders", 5);
        row.put("invoice_count", 4);
        row.put("overdue_invoices", 2);
        row.put("order_volume", new BigDecimal("1000.00"));
        row.put("invoice_volume", new BigDecimal("500.00"));
        when(jdbc.queryForList(contains("from suppliers"), any(Object[].class)))
                .thenReturn(List.of(row));
        when(workspace.ensureDocument(any(), any(), any(), any(), any(), any(), anyMap(), any()))
                .thenReturn(new AgentWorkspace.DocumentRef(UUID.randomUUID(), true));
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(UUID.randomUUID(), true));

        AgentResult result = agent.run(ctx());

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("suppliers", "1")
                .containsEntry("missingMetrics",
                        "Termintreue, Vollständigkeit, Reklamationsquote, Reaktionszeit");
        assertThat(result.summary()).contains("nicht messbare Kennzahlen");
    }

    @Test
    void skipsWithoutBusinessInThePeriod() {
        when(jdbc.queryForList(contains("from suppliers"), any(Object[].class))).thenReturn(List.of());

        AgentResult result = agent.run(ctx());

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("keine Bestellung");
    }

    @Test
    void scoreFallsWithOverdueOrdersAndInvoices() {
        Map<String, Object> row = Map.of("id", UUID.randomUUID(), "name", "X");
        SupplierScorecardAgent.Scorecard scorecard = SupplierScorecardAgent.score(row, 10, 5, 4, 2,
                new SupplierScorecardAgent.Weights(0.6, 0.4));

        assertThat(scorecard.score()).isEqualTo(50);
    }
}
