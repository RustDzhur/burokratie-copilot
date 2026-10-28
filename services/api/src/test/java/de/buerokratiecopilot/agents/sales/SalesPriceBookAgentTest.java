package de.buerokratiecopilot.agents.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
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

import de.buerokratiecopilot.agents.core.AgentArtifactStore;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentTaskFactory;

class SalesPriceBookAgentTest {

    private JdbcTemplate jdbc;
    private AgentArtifactStore artifacts;
    private AgentTaskFactory tasks;
    private SalesPriceBookAgent agent;
    private final UUID tenantId = UUID.randomUUID();
    private final UUID artifactId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        artifacts = mock(AgentArtifactStore.class);
        tasks = mock(AgentTaskFactory.class);
        agent = new SalesPriceBookAgent(jdbc, artifacts, tasks);
    }

    private static Map<String, Object> row(String article, String price, LocalDate date) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("article_number", article);
        row.put("description", "Schraube M8");
        row.put("unit", "Stk");
        row.put("unit_price", new BigDecimal(price));
        row.put("currency", "EUR");
        row.put("issue_date", java.sql.Date.valueOf(date));
        return row;
    }

    @Test
    void buildsThePriceBookAndFlagsUnconfirmedAndInconsistentPrices() {
        when(jdbc.queryForList(contains("from invoice_lines"), any(Object.class),
                any(Object.class))).thenReturn(List.of(
                row("A-1", "100.00", LocalDate.of(2026, 1, 1)),
                row("A-1", "130.00", LocalDate.of(2026, 2, 1))));
        when(artifacts.store(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AgentArtifactStore.Artifact(artifactId, "tenants/key/prices.csv",
                        "preisbuch.csv", "text/csv", 50, true));
        when(tasks.createIfAbsent(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AgentTaskFactory.Created(UUID.randomUUID(), true));

        AgentResult result = agent.run(new AgentContext(tenantId, UUID.randomUUID(), "manual",
                Instant.parse("2026-10-27T09:00:00Z"),
                Map.of("effectiveFrom", "2026-10-01")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("articles", "1")
                .containsEntry("unconfirmed", "1")
                .containsEntry("inconsistent", "1")
                .containsEntry("currency", "EUR")
                .containsEntry("storageKey", "tenants/key/prices.csv");
        assertThat(result.summary()).contains("Keine Preise geändert");
    }

    @Test
    void withoutSalesPricesTheAgentSkips() {
        when(jdbc.queryForList(contains("from invoice_lines"), any(Object.class),
                any(Object.class))).thenReturn(List.of());

        AgentResult result = agent.run(new AgentContext(tenantId, UUID.randomUUID(), "manual",
                Instant.parse("2026-10-27T09:00:00Z"),
                Map.of("effectiveFrom", "2026-10-01")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("Keine Verkaufspreise");
        verifyNoInteractions(artifacts, tasks);
    }
}
