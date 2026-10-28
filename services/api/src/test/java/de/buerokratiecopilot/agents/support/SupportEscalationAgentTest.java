package de.buerokratiecopilot.agents.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentWorkspace;

class SupportEscalationAgentTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AgentWorkspace workspace = mock(AgentWorkspace.class);
    private final SupportEscalationAgent agent = new SupportEscalationAgent(jdbc, workspace);
    private final UUID tenant = UUID.randomUUID();

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenant, UUID.randomUUID(), "manual", Instant.now(), inputs);
    }

    private Map<String, Object> ticket(String priority, Instant due) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", UUID.randomUUID());
        row.put("title", "Zahlung nicht verbucht");
        row.put("priority", priority);
        row.put("due_at", due == null ? null : java.sql.Timestamp.from(due));
        return row;
    }

    @Test
    void escalatesAnOverdueCriticalTicketAndNotifies() {
        UUID ticketId = UUID.randomUUID();
        Map<String, Object> row = ticket("CRITICAL", Instant.now().minusSeconds(3600));
        row.put("id", ticketId);
        when(jdbc.queryForList(contains("from tasks"), any(Object.class), any(Object.class))).thenReturn(List.of(row));
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(UUID.randomUUID(), true));
        when(workspace.notify(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);

        AgentResult result = agent.run(ctx(Map.of("ticketId", ticketId.toString(),
                "tone", "ESKALIEREND", "customerTier", "VIP")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("level", "3")
                .containsEntry("notified", "true");
        assertThat(result.produced()).extracting(AgentResult.Produced::kind)
                .contains("task", "notification");
    }

    @Test
    void doesNotEscalateWithoutASignal() {
        UUID ticketId = UUID.randomUUID();
        Map<String, Object> row = ticket("NORMAL", null);
        row.put("id", ticketId);
        when(jdbc.queryForList(contains("from tasks"), any(Object.class), any(Object.class))).thenReturn(List.of(row));

        AgentResult result = agent.run(ctx(Map.of("ticketId", ticketId.toString())));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("keine Eskalationssignale");
    }

    @Test
    void scoresSlaToneAndTierTogether() {
        SupportEscalationAgent.Escalation escalation = SupportEscalationAgent.assess(
                "ESKALIEREND", 3, "VIP", "HIGH", Instant.now().minusSeconds(60), Instant.now());

        assertThat(escalation.level()).isEqualTo(3);
        assertThat(escalation.reasons()).isNotEmpty();
        assertThat(escalation.responseHours()).isEqualTo(1);
    }
}
