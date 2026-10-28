package de.buerokratiecopilot.agents.support;

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

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentTaskFactory;

class SupportSlaReportAgentTest {

    private JdbcTemplate jdbc;
    private AgentTaskFactory tasks;
    private SupportSlaReportAgent agent;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        tasks = mock(AgentTaskFactory.class);
        agent = new SupportSlaReportAgent(jdbc, tasks);
    }

    private static Map<String, Object> row(String status, String created, String started,
                                           String completed) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", UUID.randomUUID());
        row.put("title", "Support: Rechnung doppelt");
        row.put("description", "Kanal: EMAIL\nBitte prüfen.");
        row.put("status", status);
        row.put("created_at", java.sql.Timestamp.from(Instant.parse(created)));
        row.put("started_at", started == null ? null
                : java.sql.Timestamp.from(Instant.parse(started)));
        row.put("completed_at", completed == null ? null
                : java.sql.Timestamp.from(Instant.parse(completed)));
        return row;
    }

    @Test
    void reportsResponseAndResolutionBreachesAndCreatesTheManagerTask() {
        when(jdbc.queryForList(contains("from tasks"), any(Object.class), any(Object.class),
                any(Object.class), any(Object.class))).thenReturn(List.of(
                row("DONE", "2026-10-10T08:00:00Z", "2026-10-10T13:00:00Z",
                        "2026-10-12T15:00:00Z")));
        when(tasks.createIfAbsent(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AgentTaskFactory.Created(UUID.randomUUID(), true));

        AgentResult result = agent.run(new AgentContext(tenantId, UUID.randomUUID(), "manual",
                Instant.parse("2026-10-27T09:00:00Z"),
                Map.of("periodFrom", "2026-10-01", "periodTo", "2026-10-31",
                        "targetFirstResponseMinutes", "240", "targetResolutionHours", "48")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("tickets", "1")
                .containsEntry("violations", "2")
                .containsEntry("targetFirstResponseMinutes", "240");
        assertThat(result.produced()).extracting(AgentResult.Produced::kind)
                .contains("sla-violation", "task");
        assertThat(result.summary()).contains("Zur Führungskraft vorgelegt");
    }

    @Test
    void withoutTicketsTheAgentSkips() {
        when(jdbc.queryForList(contains("from tasks"), any(Object.class), any(Object.class),
                any(Object.class), any(Object.class))).thenReturn(List.of());

        AgentResult result = agent.run(new AgentContext(tenantId, UUID.randomUUID(), "manual",
                Instant.parse("2026-10-27T09:00:00Z"),
                Map.of("periodFrom", "2026-10-01", "periodTo", "2026-10-31")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("Keine Support-Tickets");
        verifyNoInteractions(tasks);
    }
}
