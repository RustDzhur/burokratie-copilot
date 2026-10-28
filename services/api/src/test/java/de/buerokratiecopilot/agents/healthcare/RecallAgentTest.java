package de.buerokratiecopilot.agents.healthcare;

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

class RecallAgentTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AgentWorkspace workspace = mock(AgentWorkspace.class);
    private final RecallAgent agent = new RecallAgent(jdbc, new ObjectMapper(), workspace);
    private final UUID tenant = UUID.randomUUID();

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenant, UUID.randomUUID(), "manual", Instant.now(), inputs);
    }

    private Map<String, Object> contact(boolean consent) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", UUID.randomUUID());
        row.put("display_name", "Marta Beispiel");
        row.put("email", "marta@example.de");
        row.put("phone", null);
        row.put("mobile", null);
        row.put("metadata", "{\"recall\":{\"type\":\"Kontrolle\",\"dueDate\":\"2026-11-15\","
                + "\"consent\":" + consent + "}}");
        return row;
    }

    @Test
    void buildsAConsentedRecallListAndTasks() {
        when(jdbc.queryForList(contains("from contacts"), any(Object.class)))
                .thenReturn(List.of(contact(true)));
        when(workspace.ensureDocument(any(), any(), any(), any(), any(), any(), anyMap(), any()))
                .thenReturn(new AgentWorkspace.DocumentRef(UUID.randomUUID(), true));
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(UUID.randomUUID(), true));

        AgentResult result = agent.run(ctx(Map.of("recallType", "Kontrolle")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("patients", "1")
                .containsEntry("sent", "false");
        assertThat(result.summary()).contains("nichts versendet");
    }

    @Test
    void excludesAContactWithoutConsent() {
        when(jdbc.queryForList(contains("from contacts"), any(Object.class)))
                .thenReturn(List.of(contact(false)));

        AgentResult result = agent.run(ctx(Map.of("recallType", "Kontrolle")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("ohne Einwilligung übersprungen");
    }
}
