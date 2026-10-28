package de.buerokratiecopilot.agents.core;

import java.util.LinkedHashMap;
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

    /**
     * An honest refusal: nothing was done and the caller can see <em>why</em> in machine-readable
     * form. The catalogue agents use reason codes such as {@code NO_INPUT}, {@code NO_DATA},
     * {@code NOT_FOUND} or {@code NO_SOURCE} so that a dispatcher can distinguish "nothing to do"
     * from "the data is missing" without parsing the German summary.
     */
    public static AgentResult refused(String reason, String summary) {
        return refused(reason, summary, Map.of());
    }

    public static AgentResult refused(String reason, String summary, Map<String, String> extra) {
        Map<String, String> meta = new LinkedHashMap<>();
        if (extra != null) {
            meta.putAll(extra);
        }
        meta.put("reason", reason == null || reason.isBlank() ? "UNKNOWN" : reason);
        return new AgentResult(false, summary, List.of(), Map.copyOf(meta));
    }
}
