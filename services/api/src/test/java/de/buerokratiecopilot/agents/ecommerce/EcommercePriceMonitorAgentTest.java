package de.buerokratiecopilot.agents.ecommerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentTaskFactory;

class EcommercePriceMonitorAgentTest {

    private JdbcTemplate jdbc;
    private AgentTaskFactory tasks;
    private EcommercePriceMonitorAgent agent;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        tasks = mock(AgentTaskFactory.class);
        agent = new EcommercePriceMonitorAgent(jdbc, tasks);
    }

    private static Map<String, Object> row(String article, String price, String direction,
                                           String supplier, LocalDate date) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("article_number", article);
        row.put("description", "Schraube M8");
        row.put("unit", "Stk");
        row.put("unit_price", new BigDecimal(price));
        row.put("currency", "EUR");
        row.put("direction", direction);
        row.put("issue_date", java.sql.Date.valueOf(date));
        row.put("supplier_name", supplier);
        return row;
    }

    @Test
    void reportsSupplierSpreadAndCreatesOneReviewTask() {
        when(jdbc.queryForList(contains("from invoice_lines"), any(Object.class),
                any(Object.class))).thenReturn(List.of(
                row("A-1", "100.00", "INCOMING", "Lieferant A", LocalDate.of(2026, 9, 1)),
                row("A-1", "130.00", "INCOMING", "Lieferant B", LocalDate.of(2026, 9, 2)),
                row("A-1", "160.00", "OUTGOING", null, LocalDate.of(2026, 9, 3))));
        when(tasks.createIfAbsent(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AgentTaskFactory.Created(UUID.randomUUID(), true));

        AgentResult result = agent.run(new AgentContext(tenantId, UUID.randomUUID(), "manual",
                Instant.parse("2026-10-27T09:00:00Z"),
                Map.of("marginFloorPercent", "20", "maxChangePercent", "10")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("articlesChecked", "1")
                .containsEntry("deviations", "1")
                .containsEntry("source", "invoice_lines");
        assertThat(result.produced()).extracting(AgentResult.Produced::ref)
                .contains("A-1");
        assertThat(result.summary()).contains("Keine Shop-Preise");
    }

    @Test
    void withoutMarginFloorTheAgentSkipsHonestly() {
        AgentResult result = agent.run(new AgentContext(tenantId, UUID.randomUUID(), "manual",
                Instant.parse("2026-10-27T09:00:00Z"), Map.of()));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("marginFloorPercent fehlt");
        verifyNoInteractions(jdbc, tasks);
    }

    @Test
    void noPriceRowsIsSkipped() {
        when(jdbc.queryForList(contains("from invoice_lines"), any(Object.class),
                any(Object.class))).thenReturn(List.of());

        AgentResult result = agent.run(new AgentContext(tenantId, UUID.randomUUID(), "manual",
                Instant.parse("2026-10-27T09:00:00Z"), Map.of("marginFloorPercent", "20")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("Keine Preiszeilen");
        verifyNoInteractions(tasks);
    }
}
