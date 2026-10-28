package de.buerokratiecopilot.agents.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.core.AgentArtifactStore;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentTaskFactory;

class LogisticsRoutePlanningAgentTest {

    private AgentArtifactStore artifacts;
    private AgentTaskFactory tasks;
    private LogisticsRoutePlanningAgent agent;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        artifacts = mock(AgentArtifactStore.class);
        tasks = mock(AgentTaskFactory.class);
        agent = new LogisticsRoutePlanningAgent(artifacts, tasks, new ObjectMapper());
    }

    @Test
    void buildsATourArtifactAndAConfirmationTask() {
        when(artifacts.store(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AgentArtifactStore.Artifact(UUID.randomUUID(),
                        "tenants/key/tour.csv", "tourenplan.csv", "text/csv", 120, true));
        when(tasks.createIfAbsent(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AgentTaskFactory.Created(UUID.randomUUID(), true));

        AgentResult result = agent.run(new AgentContext(tenantId, UUID.randomUUID(), "manual",
                Instant.parse("2026-10-27T09:00:00Z"),
                Map.of("deliveryDate", "2026-10-28", "stops", """
                        [
                          {"orderId":"A-1","address":"Hauptstr. 1","timeFrom":"08:00",
                           "timeTo":"10:00","weight":10},
                          {"orderId":"A-2","address":"Hauptstr. 2","timeFrom":"09:00",
                           "timeTo":"11:00","weight":5}
                        ]
                        """, "vehicleCapacity", "{\"weight\":20}")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("stops", "2")
                .containsEntry("tours", "1")
                .containsEntry("conflicts", "0");
        assertThat(result.produced()).extracting(AgentResult.Produced::kind)
                .contains("route-plan", "task");
        assertThat(result.summary()).contains("Disposition entscheidet");
    }

    @Test
    void withoutStopsNothingIsPlanned() {
        AgentResult result = agent.run(new AgentContext(tenantId, UUID.randomUUID(), "manual",
                Instant.parse("2026-10-27T09:00:00Z"),
                Map.of("deliveryDate", "2026-10-28")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("stops fehlt");
        verifyNoInteractions(artifacts, tasks);
    }

    @Test
    void aStopOutsideItsWindowIsReportedAsConflict() {
        when(artifacts.store(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AgentArtifactStore.Artifact(UUID.randomUUID(),
                        "tenants/key/tour.csv", "tourenplan.csv", "text/csv", 120, true));
        when(tasks.createIfAbsent(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AgentTaskFactory.Created(UUID.randomUUID(), true));

        AgentResult result = agent.run(new AgentContext(tenantId, UUID.randomUUID(), "manual",
                Instant.parse("2026-10-27T09:00:00Z"),
                Map.of("deliveryDate", "2026-10-28", "stops", """
                        [{"orderId":"A-1","address":"Hauptstr. 1","timeFrom":"06:00",
                          "timeTo":"06:30"}]
                        """)));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("conflicts", "1");
        assertThat(result.produced()).extracting(AgentResult.Produced::kind)
                .contains("route-conflict");
    }
}
