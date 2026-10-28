package de.buerokratiecopilot.agents.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentWorkspace;
import de.buerokratiecopilot.agents.crm.CrmStore;

class FollowupCadenceAgentTest {

    private final CrmStore store = mock(CrmStore.class);
    private final AgentWorkspace workspace = mock(AgentWorkspace.class);
    private final FollowupCadenceAgent agent = new FollowupCadenceAgent(store, workspace);
    private final UUID tenant = UUID.randomUUID();

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenant, UUID.randomUUID(), "manual", Instant.now(), inputs);
    }

    @Test
    void plansEveryCadenceStepAsATask() {
        UUID dealId = UUID.randomUUID();
        when(store.dealInfo(tenant, dealId))
                .thenReturn(Optional.of(new CrmStore.DealInfo("PROPOSAL", null)));
        when(store.lastActivityAt(tenant, dealId, null)).thenReturn(Optional.empty());
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(UUID.randomUUID(), true));

        AgentResult result = agent.run(ctx(Map.of("dealId", dealId.toString(),
                "offerSentAt", "2026-11-01")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("steps", "4")
                .containsEntry("tasksCreated", "4");
    }

    @Test
    void stopsWhenTheDealIsAlreadyWon() {
        UUID dealId = UUID.randomUUID();
        when(store.dealInfo(tenant, dealId))
                .thenReturn(Optional.of(new CrmStore.DealInfo("WON", null)));

        AgentResult result = agent.run(ctx(Map.of("dealId", dealId.toString())));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("abgeschlossen");
    }

    @Test
    void stopsWhenThereWasAReactionAfterTheOffer() {
        UUID dealId = UUID.randomUUID();
        when(store.dealInfo(tenant, dealId))
                .thenReturn(Optional.of(new CrmStore.DealInfo("NEGOTIATION", null)));
        when(store.lastActivityAt(tenant, dealId, null))
                .thenReturn(Optional.of(Instant.parse("2026-11-05T10:00:00Z")));

        AgentResult result = agent.run(ctx(Map.of("dealId", dealId.toString(),
                "offerSentAt", "2026-11-01")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("Kadenz endet");
    }

    @Test
    void parsesAndSortsTheCadence() {
        assertThat(FollowupCadenceAgent.offsets("30,3,7,3")).containsExactly(3, 7, 30);
    }
}
