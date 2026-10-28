package de.buerokratiecopilot.agents.realestate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentWorkspace;

class MaintenanceAgentTest {

    private final AgentWorkspace workspace = mock(AgentWorkspace.class);
    private final MaintenanceAgent agent = new MaintenanceAgent(workspace);
    private final UUID tenant = UUID.randomUUID();

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenant, UUID.randomUUID(), "manual", Instant.now(), inputs);
    }

    @Test
    void classifiesAnEmergencyAndNotifiesWithoutCommissioning() {
        when(workspace.ensureDocument(any(), any(), any(), any(), any(), any(), anyMap(), any()))
                .thenReturn(new AgentWorkspace.DocumentRef(UUID.randomUUID(), true));
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(UUID.randomUUID(), true));
        when(workspace.notify(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);

        AgentResult result = agent.run(ctx(Map.of("propertyId", UUID.randomUUID().toString(),
                "issueDescription", "Wasser läuft aus dem Rohr in der Küche",
                "urgency", "EMERGENCY")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("trade", "Sanitär/Wasser")
                .containsEntry("urgency", "EMERGENCY")
                .containsEntry("notified", "true")
                .containsEntry("commissioned", "false");
    }

    @Test
    void skipsWithoutAPropertyOrIssue() {
        AgentResult result = agent.run(ctx(Map.of("urgency", "HIGH")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("propertyId");
    }

    @Test
    void classifiesElectricalIssues() {
        assertThat(MaintenanceAgent.classify("Sicherung fliegt raus, kein Strom").label())
                .isEqualTo("Elektro");
    }
}
