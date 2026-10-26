package de.buerokratiecopilot.agents.compliance;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;

/**
 * {@code risk-flag} — Risiko-Hinweise.
 *
 * <p>The Radar rule engine was removed from this edition, so there are no workspace risk rules
 * to evaluate. The agent answers honestly instead of pretending to scan: without the rule
 * engine, risk flagging has nothing to run.</p>
 */
@Component
public class RiskFlagAgent implements Agent {

    @Override
    public String id() {
        return "risk-flag";
    }

    @Override
    public AgentResult run(AgentContext context) {
        return new AgentResult(false,
                "Risiko-Hinweise nicht verfügbar: die Radar-Regel-Engine wurde in dieser Edition "
                        + "entfernt — es gibt keine Regeln zum Auswerten.",
                List.of(),
                Map.of("reason", "radar-module-removed"));
    }
}
