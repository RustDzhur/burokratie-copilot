package de.buerokratiecopilot.agents.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentTaskFactory;

class SalesPipelineReviewAgentTest {

    private JdbcTemplate jdbc;
    private AgentTaskFactory tasks;
    private SalesPipelineReviewAgent agent;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        tasks = mock(AgentTaskFactory.class);
        agent = new SalesPipelineReviewAgent(jdbc, tasks, new ObjectMapper());
    }

    private static Map<String, Object> row(String metadata) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", UUID.randomUUID());
        row.put("title", "Deal");
        row.put("owner_user_id", null);
        row.put("updated_at", java.sql.Timestamp.from(Instant.parse("2026-10-01T09:00:00Z")));
        row.put("metadata", metadata);
        return row;
    }

    @Test
    void findsAStalledDealAndCreatesOneReviewTask() {
        when(jdbc.queryForList(contains("from cases"), any(Object.class), any(Object.class)))
                .thenReturn(List.of(row("{\"deal\":{\"stage\":\"PROPOSAL\","
                        + "\"lastMovedAt\":\"2026-01-01T09:00:00Z\","
                        + "\"nextStep\":\"Angebot senden\","
                        + "\"expectedCloseDate\":\"2026-12-01\",\"amount\":1000}}")));
        when(tasks.createIfAbsent(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AgentTaskFactory.Created(UUID.randomUUID(), true));

        AgentResult result = agent.run(new AgentContext(tenantId, UUID.randomUUID(), "manual",
                Instant.parse("2026-10-27T09:00:00Z"),
                Map.of("periodWeeks", "4")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("deals", "1")
                .containsEntry("findings", "1")
                .containsEntry("stalled", "1");
        assertThat(result.summary()).contains("Stillstand");
    }

    @Test
    void withoutOpenDealsTheAgentSkips() {
        when(jdbc.queryForList(contains("from cases"), any(Object.class), any(Object.class)))
                .thenReturn(List.of());

        AgentResult result = agent.run(new AgentContext(tenantId, UUID.randomUUID(), "manual",
                Instant.parse("2026-10-27T09:00:00Z"), Map.of()));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("Keine offenen Deals");
        verifyNoInteractions(tasks);
    }

    @Test
    void badOwnerFilterFailsWithoutQuerying() {
        AgentResult result = agent.run(new AgentContext(tenantId, UUID.randomUUID(), "manual",
                Instant.parse("2026-10-27T09:00:00Z"), Map.of("ownerUserId", "nope")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("ownerUserId");
        verifyNoInteractions(jdbc, tasks);
    }
}
