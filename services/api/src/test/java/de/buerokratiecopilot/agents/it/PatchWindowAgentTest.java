package de.buerokratiecopilot.agents.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentWorkspace;

class PatchWindowAgentTest {

    private final AgentWorkspace workspace = mock(AgentWorkspace.class);
    private final PatchWindowAgent agent = new PatchWindowAgent(workspace);
    private final UUID tenant = UUID.randomUUID();

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenant, UUID.randomUUID(), "manual",
                Instant.parse("2026-11-01T10:00:00Z"), inputs);
    }

    @Test
    void plansAWindowWithRollbackAndCalendarEntry() {
        when(workspace.ensureDocument(any(), any(), any(), any(), any(), any(), anyMap(), any()))
                .thenReturn(new AgentWorkspace.DocumentRef(UUID.randomUUID(), true));
        when(workspace.ensureEvent(any(), any(), any(), any(), any(), any()))
                .thenReturn(new AgentWorkspace.EventRef(UUID.randomUUID(), true));
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(UUID.randomUUID(), true));

        AgentResult result = agent.run(ctx(Map.of("patchIds", "KB-1,KB-2",
                "affectedSystems", "ERP,Fileserver", "maintenanceWindow", "2026-11-07")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("patches", "2")
                .containsEntry("rollbackRequired", "true")
                .containsEntry("installed", "false");
        assertThat(result.produced()).extracting(AgentResult.Produced::kind)
                .contains("task", "calendar_event", "generated_document");
    }

    @Test
    void skipsWithoutPatches() {
        AgentResult result = agent.run(ctx(Map.of("affectedSystems", "ERP")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("patchIds");
    }

    @Test
    void proposesTheNextSaturdayWhenNoWindowIsGiven() {
        PatchWindowAgent.Window window = PatchWindowAgent.parseWindow(null,
                Instant.parse("2026-11-01T10:00:00Z"));

        assertThat(window.date()).isEqualTo(LocalDate.of(2026, 11, 7));
        assertThat(window.start()).isEqualTo(java.time.LocalTime.of(2, 0));
    }
}
