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

import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentWorkspace;

class BackupCheckAgentTest {

    private final AgentWorkspace workspace = mock(AgentWorkspace.class);
    private final BackupCheckAgent agent = new BackupCheckAgent(new ObjectMapper(), workspace);
    private final UUID tenant = UUID.randomUUID();

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenant, UUID.randomUUID(), "manual", Instant.now(), inputs);
    }

    @Test
    void reportsFailuresAndGapsAndCreatesATask() {
        when(workspace.ensureDocument(any(), any(), any(), any(), any(), any(), anyMap(), any()))
                .thenReturn(new AgentWorkspace.DocumentRef(UUID.randomUUID(), true));
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(UUID.randomUUID(), true));
        String runs = """
                [{"system":"ERP","startedAt":"2026-11-01T02:00:00Z","status":"SUCCESS"},
                 {"system":"ERP","startedAt":"2026-11-04T02:00:00Z","status":"FAILED"}]
                """;

        AgentResult result = agent.run(ctx(Map.of("periodFrom", "2026-11-01",
                "periodTo", "2026-11-05", "backupRuns", runs)));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("systemsWithFindings", "1");
        assertThat(result.produced()).extracting(AgentResult.Produced::kind)
                .contains("generated_document", "task");
    }

    @Test
    void skipsHonestlyWithoutDocumentedRuns() {
        AgentResult result = agent.run(ctx(Map.of("periodFrom", "2026-11-01",
                "periodTo", "2026-11-30")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("keine dokumentierten Backup-Läufe");
    }
}
