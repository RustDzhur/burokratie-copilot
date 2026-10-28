package de.buerokratiecopilot.agents.marketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentTaskFactory;

class MarketingSeaCampaignAgentTest {

    private JdbcTemplate jdbc;
    private AgentTaskFactory tasks;
    private MarketingSeaCampaignAgent agent;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        tasks = mock(AgentTaskFactory.class);
        agent = new MarketingSeaCampaignAgent(jdbc, tasks);
    }

    private static Map<String, Object> contact(String name, String organization, String website) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("display_name", name);
        row.put("organization_name", organization);
        row.put("website", website);
        return row;
    }

    @Test
    void buildsADraftFromCrmKeywordsAndCreatesAReviewTask() {
        when(jdbc.queryForList(contains("from contacts"), any(Object.class), any(Object.class)))
                .thenReturn(List.of(
                        contact("Anna Muster", "Muster Dachbau GmbH", "https://muster-dachbau.de"),
                        contact("Bernd Beispiel", "Beispiel Immobilien AG", "beispiel-immobilien.de")));
        when(tasks.createIfAbsent(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AgentTaskFactory.Created(UUID.randomUUID(), true));

        AgentResult result = agent.run(new AgentContext(tenantId, UUID.randomUUID(), "manual",
                Instant.parse("2026-10-27T09:00:00Z"),
                Map.of("periodFrom", "2026-11-01", "periodTo", "2026-11-30",
                        "monthlyBudget", "3000", "targetCpl", "25")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("ready", "true")
                .containsEntry("monthlyBudget", "3000")
                .containsEntry("maxDailyBudgetChangePercent", "20");
        assertThat(result.produced()).extracting(AgentResult.Produced::kind)
                .contains("sea-campaign-draft", "task");
        assertThat(result.summary()).contains("nichts geschaltet");
    }

    @Test
    void withoutBudgetTheAgentSkips() {
        AgentResult result = agent.run(new AgentContext(tenantId, UUID.randomUUID(), "manual",
                Instant.parse("2026-10-27T09:00:00Z"),
                Map.of("periodFrom", "2026-11-01", "periodTo", "2026-11-30")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("monthlyBudget fehlt");
        verifyNoInteractions(jdbc, tasks);
    }

    @Test
    void noKeywordSourceIsSkipped() {
        when(jdbc.queryForList(contains("from contacts"), any(Object.class), any(Object.class)))
                .thenReturn(List.of());

        AgentResult result = agent.run(new AgentContext(tenantId, UUID.randomUUID(), "manual",
                Instant.parse("2026-10-27T09:00:00Z"),
                Map.of("periodFrom", "2026-11-01", "periodTo", "2026-11-30",
                        "monthlyBudget", "3000")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("Keine Keywords");
        verifyNoInteractions(tasks);
    }
}
