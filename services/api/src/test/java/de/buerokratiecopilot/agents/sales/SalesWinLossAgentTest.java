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

class SalesWinLossAgentTest {

    private JdbcTemplate jdbc;
    private SalesWinLossAgent agent;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        agent = new SalesWinLossAgent(jdbc, new ObjectMapper());
    }

    private static Map<String, Object> row(String metadata, String organization) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", UUID.randomUUID());
        row.put("title", "Deal");
        row.put("metadata", metadata);
        row.put("updated_at", java.sql.Timestamp.from(Instant.parse("2026-10-10T09:00:00Z")));
        row.put("closed_at", null);
        row.put("organization_name", organization);
        row.put("display_name", organization);
        row.put("contact_metadata", "{\"industry\":\"Bau\"}");
        return row;
    }

    @Test
    void aggregatesWonAndLostDealsWithReasons() {
        when(jdbc.queryForList(contains("from cases"), any(Object.class), any(Object.class)))
                .thenReturn(List.of(
                        row("{\"deal\":{\"stage\":\"WON\",\"wonAt\":\"2026-10-10T09:00:00Z\","
                                + "\"winReason\":\"Preis\"}}", "Kunde A"),
                        row("{\"deal\":{\"stage\":\"LOST\",\"lostAt\":\"2026-10-11T09:00:00Z\","
                                + "\"lostReason\":\"Wettbewerb\"}}", "Kunde B"),
                        row("{\"deal\":{\"stage\":\"LOST\",\"lostAt\":\"2026-10-12T09:00:00Z\"}}",
                                "Kunde C")));

        AgentResult result = agent.run(new AgentContext(tenantId, UUID.randomUUID(), "manual",
                Instant.parse("2026-10-27T09:00:00Z"),
                Map.of("periodFrom", "2026-10-01", "periodTo", "2026-10-31")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("deals", "3")
                .containsEntry("won", "1")
                .containsEntry("lost", "2")
                .containsEntry("withoutReason", "1")
                .containsEntry("winRatePercent", "33.3");
        assertThat(result.summary()).contains("Gewonnen-Verloren-Analyse");
    }

    @Test
    void withoutClosedDealsTheAgentSkips() {
        when(jdbc.queryForList(contains("from cases"), any(Object.class), any(Object.class)))
                .thenReturn(List.of());

        AgentResult result = agent.run(new AgentContext(tenantId, UUID.randomUUID(), "manual",
                Instant.parse("2026-10-27T09:00:00Z"),
                Map.of("periodFrom", "2026-10-01", "periodTo", "2026-10-31")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("Keine abgeschlossenen Deals");
    }

    @Test
    void withoutPeriodTheAgentSkipsBeforeReading() {
        AgentResult result = agent.run(new AgentContext(tenantId, UUID.randomUUID(), "manual",
                Instant.parse("2026-10-27T09:00:00Z"), Map.of()));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("periodFrom und periodTo");
        verifyNoInteractions(jdbc);
    }
}
