package de.buerokratiecopilot.agents.hr;

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

import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentWorkspace;

class TrainingAgentTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AgentWorkspace workspace = mock(AgentWorkspace.class);
    private final TrainingAgent agent = new TrainingAgent(jdbc, new ObjectMapper(), workspace);
    private final UUID tenant = UUID.randomUUID();

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenant, UUID.randomUUID(), "manual", Instant.now(), inputs);
    }

    private Map<String, Object> employee() {
        Map<String, Object> employee = new LinkedHashMap<>();
        employee.put("id", UUID.randomUUID());
        employee.put("display_name", "Marta Beispiel");
        return employee;
    }

    @Test
    void plansRequiredTrainingsForActiveEmployees() {
        when(jdbc.queryForList(contains("from users"), any(Object[].class)))
                .thenReturn(List.of(employee()));
        when(workspace.ensureDocument(any(), any(), any(), any(), any(), any(), anyMap(), any()))
                .thenReturn(new AgentWorkspace.DocumentRef(UUID.randomUUID(), true));
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(UUID.randomUUID(), true));

        AgentResult result = agent.run(ctx(Map.of("requiredTrainings", "Datenschutz,IT-Sicherheit")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("planned", "2")
                .containsEntry("employees", "1");
    }

    @Test
    void skipsWithoutRequiredTrainings() {
        AgentResult result = agent.run(ctx(Map.of("employeeIds", UUID.randomUUID().toString())));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("requiredTrainings");
    }

    @Test
    void doesNotCountATrainingWithoutAProof() {
        Map<String, Object> employee = employee();
        when(jdbc.queryForList(contains("from users"), any(Object[].class)))
                .thenReturn(List.of(employee));
        when(workspace.ensureDocument(any(), any(), any(), any(), any(), any(), anyMap(), any()))
                .thenReturn(new AgentWorkspace.DocumentRef(UUID.randomUUID(), true));
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(UUID.randomUUID(), true));
        String completed = """
                [{"employeeId":"%s","training":"Datenschutz"}]
                """.formatted(employee.get("id"));

        AgentResult result = agent.run(ctx(Map.of("requiredTrainings", "Datenschutz",
                "completedTrainings", completed)));

        assertThat(result.meta()).containsEntry("planned", "1")
                .containsEntry("alreadyCompletedWithProof", "0");
    }
}
