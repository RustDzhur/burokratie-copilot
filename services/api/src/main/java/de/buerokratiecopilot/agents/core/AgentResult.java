package de.buerokratiecopilot.agents.core;

import java.util.List;
import java.util.Map;

/**
 * Result of one agent run. An agent reports honestly: {@code ok=false} with an explanatory
 * {@code summary} when nothing could be done — never a fake success.
 *
 * @param produced what the run created or changed; each item is surfaced in the run journal
 *                 and in the audit trail.
 * @param meta     extra machine-readable facts (classification, amounts, counts, links).
 */
public record AgentResult(boolean ok, String summary, List<Produced> produced, Map<String, String> meta) {

    public record Produced(String kind, String ref, String label) {
    }

    public static AgentResult done(String summary, List<Produced> produced, Map<String, String> meta) {
        return new AgentResult(true, summary, produced == null ? List.of() : produced,
                meta == null ? Map.of() : meta);
    }

    public static AgentResult skipped(String summary) {
        return new AgentResult(false, summary, List.of(), Map.of());
    }

    public static AgentResult failed(String summary) {
        return new AgentResult(false, summary, List.of(), Map.of());
    }
}
