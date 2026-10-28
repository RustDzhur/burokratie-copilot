package de.buerokratiecopilot.agents.hospitality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
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

class ShiftPlanAgentTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AgentWorkspace workspace = mock(AgentWorkspace.class);
    private final ShiftPlanAgent agent = new ShiftPlanAgent(jdbc, new ObjectMapper(), workspace);
    private final UUID tenant = UUID.randomUUID();

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenant, UUID.randomUUID(), "manual", Instant.now(), inputs);
    }

    @Test
    void createsAWeeklyProposalWithoutCommittingARoster() {
        Map<String, Object> employee = new LinkedHashMap<>();
        employee.put("id", UUID.randomUUID());
        employee.put("display_name", "Marta");
        when(jdbc.queryForList(contains("from users"), any(Object[].class)))
                .thenReturn(List.of(employee));
        when(workspace.ensureDocument(any(), any(), any(), any(), any(), any(), anyMap(), any()))
                .thenReturn(new AgentWorkspace.DocumentRef(UUID.randomUUID(), true));
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(UUID.randomUUID(), true));

        AgentResult result = agent.run(ctx(Map.of("week", "2026-W45", "expectedCovers", "30")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("week", "2026-W45")
                .containsEntry("employees", "1")
                .containsEntry("rosterCommitted", "false");
    }

    @Test
    void skipsAnInvalidWeek() {
        AgentResult result = agent.run(ctx(Map.of("week", "45/2026")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("YYYY-Www");
    }

    @Test
    void parsesTheIsoWeekToItsMonday() {
        assertThat(ShiftPlanAgent.parseWeek("2026-W45").monday())
                .isEqualTo(LocalDate.of(2026, 11, 2));
    }
}
