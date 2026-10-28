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

import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentTaskFactory;

class SupportFaqSuggestAgentTest {

    private JdbcTemplate jdbc;
    private AgentTaskFactory tasks;
    private SupportFaqSuggestAgent agent;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        tasks = mock(AgentTaskFactory.class);
        agent = new SupportFaqSuggestAgent(jdbc, tasks, new ObjectMapper());
    }

    private static Map<String, Object> row(String title) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", UUID.randomUUID());
        row.put("title", title);
        row.put("description", "Kanal: EMAIL\nBitte Rechnung und Belegnummer prüfen.");
        row.put("created_at", java.sql.Timestamp.from(Instant.parse("2026-10-10T09:00:00Z")));
        row.put("tags", "[\"support\",\"RECHNUNG\"]");
        return row;
    }

    @Test
    void clustersRecurringRequestsAndCreatesEditorialTasks() {
        when(jdbc.queryForList(contains("from tasks"), any(Object.class), any(Object.class),
                any(Object.class), any(Object.class))).thenReturn(List.of(
                row("Support: Rechnung doppelt"),
                row("Support: Rechnung doppelt"),
                row("Support: Rechnung doppelt")));
        when(tasks.createIfAbsent(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AgentTaskFactory.Created(UUID.randomUUID(), true));

        AgentResult result = agent.run(new AgentContext(tenantId, UUID.randomUUID(), "manual",
                Instant.parse("2026-10-27T09:00:00Z"),
                Map.of("periodFrom", "2026-10-01", "periodTo", "2026-10-31",
                        "minOccurrences", "3")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("tickets", "3")
                .containsEntry("candidates", "1")
                .containsEntry("tasksCreated", "1");
        assertThat(result.produced()).extracting(AgentResult.Produced::kind)
                .contains("faq-candidate");
        assertThat(result.summary()).contains("Nichts veröffentlicht");
    }

    @Test
    void withoutSupportTicketsTheAgentSkips() {
        when(jdbc.queryForList(contains("from tasks"), any(Object.class), any(Object.class),
                any(Object.class), any(Object.class))).thenReturn(List.of());

        AgentResult result = agent.run(new AgentContext(tenantId, UUID.randomUUID(), "manual",
                Instant.parse("2026-10-27T09:00:00Z"),
                Map.of("periodFrom", "2026-10-01", "periodTo", "2026-10-31")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("Keine Support-Anfragen");
        verifyNoInteractions(tasks);
    }

    @Test
    void withoutPeriodNothingIsRead() {
        AgentResult result = agent.run(new AgentContext(tenantId, UUID.randomUUID(), "manual",
                Instant.parse("2026-10-27T09:00:00Z"), Map.of()));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("periodFrom und periodTo");
        verifyNoInteractions(jdbc, tasks);
    }
}
