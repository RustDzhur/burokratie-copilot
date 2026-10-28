package de.buerokratiecopilot.agents.marketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentWorkspace;
import de.buerokratiecopilot.agents.crm.CrmStore;

class LeadNurtureAgentTest {

    private final CrmStore store = mock(CrmStore.class);
    private final AgentWorkspace workspace = mock(AgentWorkspace.class);
    private final LeadNurtureAgent agent = new LeadNurtureAgent(store, workspace);
    private final UUID tenant = UUID.randomUUID();

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenant, UUID.randomUUID(), "manual", Instant.now(), inputs);
    }

    private Map<String, Object> contactRow(String stage) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", UUID.randomUUID());
        row.put("display_name", "Marta Beispiel");
        row.put("email", "marta@example.de");
        row.put("phone", null);
        row.put("mobile", null);
        row.put("metadata", Map.of("lead", Map.of("stage", stage), "source", "website"));
        return row;
    }

    @Test
    void plansTheNextNurturingStepAndRecordsIt() {
        UUID contactId = UUID.randomUUID();
        when(store.findContactRow(tenant, contactId)).thenReturn(Optional.of(contactRow("NEW")));
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(UUID.randomUUID(), true));
        when(store.appendContactActivity(any(), any(), any())).thenReturn(1);

        AgentResult result = agent.run(ctx(Map.of("contactId", contactId.toString(),
                "industry", "Handwerk")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("phase", "AWARENESS")
                .containsEntry("action", "Informationsangebot")
                .containsEntry("activityRecorded", "true");
    }

    @Test
    void skipsAClosedLead() {
        UUID contactId = UUID.randomUUID();
        when(store.findContactRow(tenant, contactId)).thenReturn(Optional.of(contactRow("WON")));

        AgentResult result = agent.run(ctx(Map.of("contactId", contactId.toString())));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("abgeschlossen");
    }

    @Test
    void pausesAnInactiveLeadInsteadOfPushingIt() {
        LeadNurtureAgent.Nurture nurture = LeadNurtureAgent.decide("CONTACTED", 45, true);

        assertThat(nurture.phase()).isEqualTo("PAUSE");
        assertThat(nurture.dueDays()).isEqualTo(30);
    }
}
