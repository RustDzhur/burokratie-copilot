package de.buerokratiecopilot.agents.hr;

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

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentWorkspace;

class LeaveRequestAgentTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AgentWorkspace workspace = mock(AgentWorkspace.class);
    private final LeaveRequestAgent agent = new LeaveRequestAgent(jdbc, workspace);
    private final UUID tenant = UUID.randomUUID();

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenant, UUID.randomUUID(), "manual", Instant.now(), inputs);
    }

    @Test
    void checksAnEmployeeRequestAndFilesADecisionTask() {
        UUID employeeId = UUID.randomUUID();
        Map<String, Object> employee = new LinkedHashMap<>();
        employee.put("id", employeeId);
        employee.put("display_name", "Marta Beispiel");
        when(jdbc.queryForList(contains("from users"), any(Object.class), any(Object.class))).thenReturn(List.of(employee));
        when(jdbc.queryForList(contains("from tasks"), any(Object.class), any(Object.class), any(Object.class), any(Object.class)))
                .thenReturn(List.of());
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(UUID.randomUUID(), true));
        when(workspace.ensureDocument(any(), any(), any(), any(), any(), any(), anyMap(), any()))
                .thenReturn(new AgentWorkspace.DocumentRef(UUID.randomUUID(), true));

        AgentResult result = agent.run(ctx(Map.of("employeeId", employeeId.toString(),
                "from", "2026-11-02", "to", "2026-11-06")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("workingDays", "5")
                .containsEntry("approved", "false");
        assertThat(result.summary()).contains("Hinweis");
    }

    @Test
    void skipsAnEmployeeWhoIsNotActiveInTheTenant() {
        when(jdbc.queryForList(contains("from users"), any(Object.class), any(Object.class))).thenReturn(List.of());

        AgentResult result = agent.run(ctx(Map.of("employeeId", UUID.randomUUID().toString(),
                "from", "2026-11-02", "to", "2026-11-06")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("nicht aktiv");
    }

    @Test
    void countsWorkingDaysWithoutWeekends() {
        assertThat(LeaveRequestAgent.workingDays(LocalDate.of(2026, 11, 2),
                LocalDate.of(2026, 11, 8))).isEqualTo(5);
    }
}
