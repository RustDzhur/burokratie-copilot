package de.buerokratiecopilot.agents.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.radar.RadarEngine;
import de.buerokratiecopilot.radar.RadarQueryService;
import de.buerokratiecopilot.settings.RuleSettingsService;

/**
 * Working behaviour of the risk flag: it runs the Radar over a document or the whole workspace
 * and reports honestly what the rules found — including when all rules fail.
 */
@ExtendWith(MockitoExtension.class)
class RiskFlagAgentTest {

    @Mock
    private RadarEngine engine;
    @Mock
    private RuleSettingsService ruleSettings;
    @Mock
    private RadarQueryService radarQueries;

    private RiskFlagAgent agent;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        agent = new RiskFlagAgent(engine, ruleSettings, radarQueries);
    }

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenantId, UUID.randomUUID(), "manual", null, inputs);
    }

    private void radarEnabled(boolean enabled) {
        when(ruleSettings.list(any())).thenReturn(List.of(Map.of("key", "radar.enabled", "value", enabled)));
    }

    @Test
    void skipsHonestlyWhenRadarIsDisabled() {
        radarEnabled(false);

        AgentResult result = agent.run(ctx(Map.of()));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("deaktiviert");
        verify(engine, never()).sweep(any());
        verify(engine, never()).evaluateDocument(any(), any());
    }

    @Test
    void reportsSweepFindingsAndNotification() {
        radarEnabled(true);
        when(engine.sweep(any())).thenReturn(new RadarEngine.Report(2, 1, 0, 3, 1, 0));
        when(radarQueries.summary(any(), any())).thenReturn(Map.of("openBySeverity", Map.of("HIGH", 2)));

        AgentResult result = agent.run(ctx(Map.of()));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta().get("created")).isEqualTo("2");
        assertThat(result.meta().get("reopened")).isEqualTo("1");
        assertThat(result.meta().get("resolved")).isEqualTo("1");
        assertThat(result.summary()).contains("benachrichtigt");
    }

    @Test
    void evaluatesOneDocumentWithoutSweep() {
        radarEnabled(true);
        when(engine.evaluateDocument(any(), any())).thenReturn(new RadarEngine.Report(1, 0, 0, 0, 0, 0));

        AgentResult result = agent.run(ctx(Map.of("documentId", UUID.randomUUID().toString(), "sweep", "false")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta().get("created")).isEqualTo("1");
        verify(engine, never()).sweep(any());
    }

    @Test
    void reportsFailureWhenAllRulesFail() {
        radarEnabled(true);
        when(engine.sweep(any())).thenReturn(new RadarEngine.Report(0, 0, 0, 0, 0, 3));

        AgentResult result = agent.run(ctx(Map.of()));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("fehlgeschlagen");
    }

    @Test
    void reportsHonestZeroWhenNothingFound() {
        radarEnabled(true);
        when(engine.sweep(any())).thenReturn(new RadarEngine.Report(0, 0, 0, 0, 0, 0));

        AgentResult result = agent.run(ctx(Map.of()));

        assertThat(result.ok()).isTrue();
        assertThat(result.summary()).contains("keine neuen Befunde");
    }
}
