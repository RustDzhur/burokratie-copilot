package de.buerokratiecopilot.agents.marketing;

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

class SeoAuditAgentTest {

    private final AgentWorkspace workspace = mock(AgentWorkspace.class);
    private final SeoAuditAgent agent = new SeoAuditAgent(new ObjectMapper(), workspace);
    private final UUID tenant = UUID.randomUUID();

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenant, UUID.randomUUID(), "manual", Instant.now(), inputs);
    }

    @Test
    void auditsSuppliedPageSignalsAndFilesMeasures() {
        when(workspace.ensureDocument(any(), any(), any(), any(), any(), any(), anyMap(), any()))
                .thenReturn(new AgentWorkspace.DocumentRef(UUID.randomUUID(), true));
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(UUID.randomUUID(), true));
        String pages = """
                [{"url":"https://example.de/archiv","title":"Archiv","metaDescription":"",
                  "h1Count":2,"internalLinks":0,"wordCount":120}]
                """;

        AgentResult result = agent.run(ctx(Map.of("siteUrl", "https://example.de",
                "pages", pages, "targetKeywords", "archiv")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("pages", "1")
                .containsEntry("findingPages", "1")
                .containsEntry("crawl", "false");
    }

    @Test
    void skipsHonestlyWithoutPageFacts() {
        AgentResult result = agent.run(ctx(Map.of("siteUrl", "https://example.de")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("keine Seitendaten");
    }

    @Test
    void flagsMissingTitleAndMetaButNotAHealthyPage() {
        List<SeoAuditAgent.PageAudit> broken = SeoAuditAgent.audit(List.of(Map.of(
                "url", "https://example.de/x", "h1Count", 1, "internalLinks", 3)), List.of());
        List<SeoAuditAgent.PageAudit> healthy = SeoAuditAgent.audit(List.of(Map.of(
                "url", "https://example.de/y", "title", "Ein sauberer Seitentitel für den Test",
                "metaDescription", "Eine ausreichend lange Meta-Beschreibung für die Prüfung im Test.",
                "h1Count", 1, "internalLinks", 3, "wordCount", 600)), List.of());

        assertThat(broken.getFirst().findings()).isNotEmpty();
        assertThat(healthy.getFirst().findings()).isEmpty();
    }
}
