package de.buerokratiecopilot.agents.marketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentWorkspace;

class SocialCalendarAgentTest {

    private final AgentWorkspace workspace = mock(AgentWorkspace.class);
    private final SocialCalendarAgent agent = new SocialCalendarAgent(workspace);
    private final UUID tenant = UUID.randomUUID();

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenant, UUID.randomUUID(), "manual", Instant.now(), inputs);
    }

    @Test
    void plansPostsAndCreatesCalendarEntriesWithoutPublishing() {
        when(workspace.ensureDocument(any(), any(), any(), any(), any(), any(), anyMap(), any()))
                .thenReturn(new AgentWorkspace.DocumentRef(UUID.randomUUID(), true));
        when(workspace.ensureEvent(any(), any(), any(), any(), any(), any()))
                .thenReturn(new AgentWorkspace.EventRef(UUID.randomUUID(), true));
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(UUID.randomUUID(), true));

        AgentResult result = agent.run(ctx(Map.of("startDate", "2026-11-02",
                "weeks", "1", "channels", "LinkedIn,Instagram")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("posts", "4")
                .containsEntry("calendarEntriesCreated", "4");
        assertThat(result.summary()).contains("nichts veröffentlicht");
    }

    @Test
    void skipsWithoutAStartDate() {
        AgentResult result = agent.run(ctx(Map.of("weeks", "4")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("startDate");
    }

    @Test
    void plansTwoPostsPerChannelAndWeek() {
        List<SocialCalendarAgent.Post> posts = SocialCalendarAgent.plan(LocalDate.of(2026, 11, 2), 2,
                List.of("LinkedIn"), List.of("Fachwissen"), "sachlich");

        assertThat(posts).hasSize(4);
        assertThat(posts).allSatisfy(post -> assertThat(post.status()).isEqualTo("ENTWURF"));
    }
}
