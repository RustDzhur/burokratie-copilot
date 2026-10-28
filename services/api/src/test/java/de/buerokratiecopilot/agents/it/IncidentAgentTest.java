package de.buerokratiecopilot.agents.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentWorkspace;

class IncidentAgentTest {

    private final AgentWorkspace workspace = mock(AgentWorkspace.class);
    private final IncidentAgent agent = new IncidentAgent(workspace);
    private final UUID tenant = UUID.randomUUID();

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenant, UUID.randomUUID(), "manual", Instant.now(), inputs);
    }

    @Test
    void filesAnIncidentWithSeveritySlaAndProtocol() {
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(UUID.randomUUID(), true));
        when(workspace.ensureDocument(any(), any(), any(), any(), any(), any(), anyMap(), any()))
                .thenReturn(new AgentWorkspace.DocumentRef(UUID.randomUUID(), true));
        when(workspace.notify(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);

        AgentResult result = agent.run(ctx(Map.of("title", "Serverausfall produktiv",
                "description", "Niemand kann sich anmelden", "affectedSystems", "ERP, Mail")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("severity", "CRITICAL")
                .containsEntry("slaMinutes", "60")
                .containsEntry("notified", "true");
    }

    @Test
    void skipsWithoutATitle() {
        AgentResult result = agent.run(ctx(Map.of("description", "etwas")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("title");
    }

    @Test
    void readsTheSeverityHintBeforeKeywords() {
        IncidentAgent.Severity severity = IncidentAgent.classify("Kleiner Fehler", null,
                List.of("Drucker"), "high");

        assertThat(severity.name()).isEqualTo("HIGH");
        assertThat(severity.priority()).isEqualTo(de.buerokratiecopilot.tasks.domain.Task.Priority.HIGH);
    }
}
