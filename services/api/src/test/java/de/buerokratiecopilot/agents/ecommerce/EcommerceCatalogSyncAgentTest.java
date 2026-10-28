package de.buerokratiecopilot.agents.ecommerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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

class EcommerceCatalogSyncAgentTest {

    private JdbcTemplate jdbc;
    private AgentTaskFactory tasks;
    private EcommerceCatalogSyncAgent agent;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        tasks = mock(AgentTaskFactory.class);
        agent = new EcommerceCatalogSyncAgent(jdbc, tasks);
    }

    private static Map<String, Object> row(String article, String title, String unit) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("article_number", article);
        row.put("description", title);
        row.put("unit", unit);
        row.put("issue_date", java.sql.Date.valueOf(LocalDate.of(2026, 9, 1)));
        row.put("supplier_name", "Lieferant");
        return row;
    }

    @Test
    void reportsConflictingTitlesAsConflictsAndCreatesATask() {
        when(jdbc.queryForList(contains("from invoice_lines"), any(Object.class),
                any(Object.class))).thenReturn(List.of(
                row("A-1", "Schraube M8", "Stk"),
                row("A-1", "Schraube M8 verzinkt", "Stk")));
        when(tasks.createIfAbsent(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AgentTaskFactory.Created(UUID.randomUUID(), true));

        AgentResult result = agent.run(new AgentContext(tenantId, UUID.randomUUID(), "manual",
                Instant.parse("2026-10-27T09:00:00Z"),
                Map.of("channel", "SHOP", "dryRun", "true")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("conflicts", "1")
                .containsEntry("articlesChecked", "1")
                .containsEntry("dryRun", "true");
        assertThat(result.produced()).extracting(AgentResult.Produced::ref).contains("A-1");
        assertThat(result.summary()).contains("Trockenlauf");
    }

    @Test
    void withoutArticleDataTheAgentSkips() {
        when(jdbc.queryForList(contains("from invoice_lines"), any(Object.class),
                any(Object.class))).thenReturn(List.of());

        AgentResult result = agent.run(new AgentContext(tenantId, UUID.randomUUID(), "manual",
                Instant.parse("2026-10-27T09:00:00Z"), Map.of()));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("Keine Artikel");
        verifyNoInteractions(tasks);
    }
}
