package de.buerokratiecopilot.agents.production;

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

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentTaskFactory;

class ProductionQualityCheckAgentTest {

    private AgentTaskFactory tasks;
    private ProductionQualityCheckAgent agent;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        tasks = mock(AgentTaskFactory.class);
        agent = new ProductionQualityCheckAgent(tasks, new ObjectMapper());
    }

    @Test
    void reportsAMeasurementOutsideToleranceAndCreatesANonconformanceTask() {
        when(tasks.createIfAbsent(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AgentTaskFactory.Created(UUID.randomUUID(), true));

        AgentResult result = agent.run(new AgentContext(tenantId, UUID.randomUUID(), "manual",
                Instant.parse("2026-10-27T09:00:00Z"),
                Map.of("workOrderId", "WO-2026-17", "measurements", """
                        [
                          {"name":"Durchmesser","value":10.5,"min":9.0,"max":10.0,"unit":"mm"},
                          {"name":"Länge","value":20.0,"min":19.0,"max":21.0,"unit":"mm"}
                        ]
                        """)));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("measurements", "2")
                .containsEntry("assessed", "2")
                .containsEntry("deviations", "1");
        assertThat(result.produced()).extracting(AgentResult.Produced::kind)
                .contains("quality-finding", "task");
        assertThat(result.summary()).contains("Keine Freigabeentscheidung");
    }

    @Test
    void withoutMeasurementsTheAgentSkips() {
        AgentResult result = agent.run(new AgentContext(tenantId, UUID.randomUUID(), "manual",
                Instant.parse("2026-10-27T09:00:00Z"), Map.of("workOrderId", "WO-1")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("measurements fehlt");
        verifyNoInteractions(tasks);
    }

    @Test
    void withoutTolerancesTheAgentDoesNotInventOne() {
        AgentResult result = agent.run(new AgentContext(tenantId, UUID.randomUUID(), "manual",
                Instant.parse("2026-10-27T09:00:00Z"),
                Map.of("workOrderId", "WO-1", "measurements",
                        "[{\"name\":\"Durchmesser\",\"value\":10.5}]")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("Keine Messung hat eine Toleranz");
        verifyNoInteractions(tasks);
    }
}
