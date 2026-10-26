package de.buerokratiecopilot.agents.finance;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;

/**
 * {@code payment-match} — Zahlungsabgleich.
 *
 * <p>The banking module (transactions, CAMT import, payment matching) was removed from this
 * edition, so there are no bank transactions to match against open invoices. The agent answers
 * honestly instead of pretending: without bank data, payment matching has nothing to do.</p>
 */
@Component
public class PaymentMatchAgent implements Agent {

    @Override
    public String id() {
        return "payment-match";
    }

    @Override
    public AgentResult run(AgentContext context) {
        return new AgentResult(false,
                "Zahlungsabgleich nicht verfügbar: das Bankmodul (Transaktionen und CAMT-Import) "
                        + "wurde in dieser Edition entfernt — es gibt keine Bankdaten zum Abgleich.",
                List.of(),
                Map.of("reason", "banking-module-removed"));
    }
}
