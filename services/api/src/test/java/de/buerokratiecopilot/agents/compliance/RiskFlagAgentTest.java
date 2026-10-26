package de.buerokratiecopilot.agents.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;

/** The Radar engine was removed from this edition; the agent reports that honestly. */

class RiskFlagAgentTest {

    private final RiskFlagAgent agent = new RiskFlagAgent();

    @Test
    void reportsUnavailableHonestly() {
        AgentResult result = agent.run(new AgentContext(
                UUID.randomUUID(), UUID.randomUUID(), "manual", null, Map.of()));
        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("Radar");
        assertThat(result.meta().get("reason")).isEqualTo("radar-module-removed");
    }
}
