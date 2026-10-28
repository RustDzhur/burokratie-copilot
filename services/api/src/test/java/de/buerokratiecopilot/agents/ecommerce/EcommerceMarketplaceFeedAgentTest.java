package de.buerokratiecopilot.agents.ecommerce;

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

import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.core.AgentArtifactStore;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentTaskFactory;

class EcommerceMarketplaceFeedAgentTest {

    private JdbcTemplate jdbc;
    private AgentArtifactStore artifacts;
    private AgentTaskFactory tasks;
    private EcommerceMarketplaceFeedAgent agent;
    private final UUID tenantId = UUID.randomUUID();
    private final UUID artifactId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        artifacts = mock(AgentArtifactStore.class);
        tasks = mock(AgentTaskFactory.class);
        agent = new EcommerceMarketplaceFeedAgent(jdbc, artifacts, tasks, new ObjectMapper());
    }

    private static Map<String, Object> row(String article, String price, String direction) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("article_number", article);
        row.put("description", "Schraube M8");
        row.put("unit", "Stk");
        row.put("unit_price", new BigDecimal(price));
        row.put("currency", "EUR");
        row.put("direction", direction);
        row.put("issue_date", java.sql.Date.valueOf(LocalDate.of(2026, 9, 1)));
        return row;
    }

    @Test
    void storesAFeedArtifactAndCreatesAReviewTask() {
        when(jdbc.queryForList(contains("from invoice_lines"), any(Object.class),
                any(Object.class))).thenReturn(List.of(
                row("A-1", "9.99", "OUTGOING"),
                row("A-2", "19.99", "INCOMING")));
        when(artifacts.store(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AgentArtifactStore.Artifact(artifactId, "tenants/key/feed.csv",
                        "feed.csv", "text/csv", 100, true));
        when(tasks.createIfAbsent(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AgentTaskFactory.Created(UUID.randomUUID(), true));

        AgentResult result = agent.run(new AgentContext(tenantId, UUID.randomUUID(), "manual",
                Instant.parse("2026-10-27T09:00:00Z"),
                Map.of("marketplace", "Amazon", "feedFormat", "CSV", "currency", "EUR")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("products", "2")
                .containsEntry("published", "false")
                .containsEntry("storageKey", "tenants/key/feed.csv");
        assertThat(result.produced()).extracting(AgentResult.Produced::kind)
                .contains("marketplace-feed");
    }

    @Test
    void withoutMarketplaceNothingIsBuilt() {
        AgentResult result = agent.run(new AgentContext(tenantId, UUID.randomUUID(), "manual",
                Instant.parse("2026-10-27T09:00:00Z"), Map.of()));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("marketplace fehlt");
        verifyNoInteractions(jdbc, artifacts, tasks);
    }

    @Test
    void noRowsIsSkipped() {
        when(jdbc.queryForList(contains("from invoice_lines"), any(Object.class),
                any(Object.class))).thenReturn(List.of());

        AgentResult result = agent.run(new AgentContext(tenantId, UUID.randomUUID(), "manual",
                Instant.parse("2026-10-27T09:00:00Z"), Map.of("marketplace", "Amazon")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("Keine Produkt-/Preisdaten");
        verifyNoInteractions(artifacts, tasks);
    }
}
