package de.buerokratiecopilot.agents.realestate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.agents.core.AgentArtifactStore;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentTaskFactory;
import de.buerokratiecopilot.agents.core.AgentTestFixtures;

/** {@code realestate-listing}: prepared exposé with gaps, release task and no publication. */
class ListingAgentTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");

    private AgentTaskFactory tasks;
    private AgentArtifactStore artifacts;
    private ListingAgent agent;

    @BeforeEach
    void setUp() {
        tasks = mock(AgentTaskFactory.class);
        artifacts = mock(AgentArtifactStore.class);
        agent = new ListingAgent(tasks, artifacts);
    }

    private AgentContext context(Map<String, String> inputs) {
        return new AgentContext(TENANT, USER, "manual", NOW, inputs);
    }

    @Test
    void preparesTheExposeWithAllMandatoryDetailsAndAReleaseTask() {
        when(tasks.createIfAbsent(any(), any()))
                .thenReturn(Optional.of(AgentTestFixtures.task(TENANT, "Exposé freigeben")));
        when(artifacts.put(any(), any(), anyString(), anyString(), any()))
                .thenReturn(AgentTestFixtures.artifact("realestate-listing"));

        AgentResult result = agent.run(context(Map.of(
                "propertyId", "OBJ-77",
                "address", "Musterstraße 1, 10115 Berlin",
                "livingArea", "78,5",
                "rooms", "3",
                "energyClass", "C",
                "price", "1250.00",
                "highlights", "Balkon, Aufzug")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("gaps", "0")
                .containsEntry("readyForPublication", "true")
                .containsEntry("published", "false")
                .containsEntry("requiresApproval", "true")
                .containsEntry("tasksCreated", "1");
        assertThat(result.produced()).extracting(AgentResult.Produced::kind)
                .contains("realestate-listing", "task");
        assertThat(result.summary()).contains("1.250,00");
    }

    @Test
    void missingAddressAndEnergyClassAreGapsNotInventions() {
        when(tasks.createIfAbsent(any(), any())).thenReturn(Optional.empty());
        when(artifacts.put(any(), any(), anyString(), anyString(), any()))
                .thenReturn(AgentTestFixtures.artifact("realestate-listing"));

        AgentResult result = agent.run(context(Map.of(
                "propertyId", "OBJ-77",
                "livingArea", "78.5",
                "rooms", "3",
                "price", "1250.00")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("gaps", "3")
                .containsEntry("readyForPublication", "false");
        assertThat(result.summary()).contains("Lücke");
    }

    @Test
    void refusesWhenAMandatoryValueIsMissing() {
        AgentResult result = agent.run(context(Map.of("propertyId", "OBJ-77",
                "livingArea", "78.5", "rooms", "3")));

        assertThat(result.ok()).isFalse();
        assertThat(result.meta()).containsEntry("reason", "MISSING_REQUIRED");
        assertThat(result.meta().get("missing")).contains("price");
        verify(artifacts, never()).put(any(), any(), anyString(), anyString(), any());
    }
}
