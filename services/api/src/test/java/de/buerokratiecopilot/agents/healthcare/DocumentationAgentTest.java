package de.buerokratiecopilot.agents.healthcare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentWorkspace;

class DocumentationAgentTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AgentWorkspace workspace = mock(AgentWorkspace.class);
    private final DocumentationAgent agent = new DocumentationAgent(jdbc, new ObjectMapper(), workspace);
    private final UUID tenant = UUID.randomUUID();

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenant, UUID.randomUUID(), "manual", Instant.now(), inputs);
    }

    @Test
    void structuresFieldsAndFlagsMissingOnesForReview() {
        when(jdbc.query(contains("from contacts"), any(RowMapper.class), any(Object.class), any(Object.class)))
                .thenReturn(List.of("Marta Beispiel"));
        when(workspace.ensureDocument(any(), any(), any(), any(), any(), any(), anyMap(), any()))
                .thenReturn(new AgentWorkspace.DocumentRef(UUID.randomUUID(), true));
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(UUID.randomUUID(), true));
        String fields = "{\"Anlass\":\"Kontrolle\",\"Verlauf\":\"unauffällig\"}";

        AgentResult result = agent.run(ctx(Map.of("patientContactId", UUID.randomUUID().toString(),
                "encounterDate", "2026-10-20", "documentType", "Verlauf", "fields", fields)));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("missingFields", "2")
                .containsEntry("humanReview", "true");
        assertThat(result.summary()).contains("Keine Diagnose");
    }

    @Test
    void skipsWithoutAnyContent() {
        when(jdbc.query(contains("from contacts"), any(RowMapper.class), any(Object.class), any(Object.class)))
                .thenReturn(List.of("Marta Beispiel"));

        AgentResult result = agent.run(ctx(Map.of("patientContactId", UUID.randomUUID().toString(),
                "encounterDate", "2026-10-20", "documentType", "Verlauf")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("weder strukturierte Felder");
    }
}
