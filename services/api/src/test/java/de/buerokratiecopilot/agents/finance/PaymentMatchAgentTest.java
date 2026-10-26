package de.buerokratiecopilot.agents.finance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;

class PaymentMatchAgentTest {

    private final PaymentMatchAgent agent = new PaymentMatchAgent();

    @Test
    void reportsUnavailableHonestly() {
        AgentResult result = agent.run(new AgentContext(
                UUID.randomUUID(), UUID.randomUUID(), "manual", null, Map.of()));
        assertFalse(result.ok());
        assertTrue(result.summary().contains("Bankmodul"));
        assertEquals("banking-module-removed", result.meta().get("reason"));
    }

    @Test
    void idMatchesManifest() {
        assertEquals("payment-match", agent.id());
    }
}
