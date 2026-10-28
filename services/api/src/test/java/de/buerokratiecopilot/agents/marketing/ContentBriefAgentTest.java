package de.buerokratiecopilot.agents.marketing;

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

class ContentBriefAgentTest {

    private final AgentWorkspace workspace = mock(AgentWorkspace.class);
    private final ContentBriefAgent agent = new ContentBriefAgent(workspace);
    private final UUID tenant = UUID.randomUUID();

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenant, UUID.randomUUID(), "manual", Instant.now(), inputs);
    }

    @Test
    void producesABriefAndAnApprovalTask() {
        when(workspace.ensureDocument(any(), any(), any(), any(), any(), any(), anyMap(), any()))
                .thenReturn(new AgentWorkspace.DocumentRef(UUID.randomUUID(), true));
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(UUID.randomUUID(), true));

        AgentResult result = agent.run(ctx(Map.of("topic", "Rechnung digital kaufen",
                "targetAudience", "kleine Kanzleien", "keyword", "rechnung digital")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("intent", "transaktional")
                .containsEntry("format", "BLOG");
        assertThat(result.summary()).contains("Quellen und Belege");
    }

    @Test
    void skipsWithoutATargetAudience() {
        AgentResult result = agent.run(ctx(Map.of("topic", "Thema")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("targetAudience");
    }

    @Test
    void informsAboutTheSearchIntent() {
        ContentBriefAgent.Brief brief = ContentBriefAgent.build("Wie funktioniert ein Archiv",
                "Büros", "archiv anleitung", "BLOG", 900, "sachlich");

        assertThat(brief.intent()).isEqualTo("informational");
        assertThat(brief.outline()).isNotEmpty();
        assertThat(brief.openPoints()).isNotEmpty();
    }
}
