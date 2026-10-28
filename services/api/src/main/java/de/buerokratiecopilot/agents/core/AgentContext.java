package de.buerokratiecopilot.agents.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything an agent may legitimately touch during one run.
 *
 * <p>Carries only identifiers, never entities: the tenant scope comes from
 * {@link de.buerokratiecopilot.shared.context.RequestContext}, and the agent works through
 * the platform services, which enforce row-level security themselves.</p>
 */
public final class AgentContext {

    private final UUID tenantId;
    private final UUID actorUserId;
    private final String trigger;
    private final Instant now;
    private final Map<String, String> inputs;
    private final List<AgentResult.Produced> produced = new ArrayList<>();

    public AgentContext(UUID tenantId, UUID actorUserId, String trigger, Instant now, Map<String, String> inputs) {
        this.tenantId = tenantId;
        this.actorUserId = actorUserId;
        this.trigger = trigger == null ? "manual" : trigger;
        this.now = now == null ? Instant.now() : now;
        this.inputs = inputs == null ? Map.of() : inputs;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID actorUserId() {
        return actorUserId;
    }

    public String trigger() {
        return trigger;
    }

    public Instant now() {
        return now;
    }

    public Map<String, String> inputs() {
        return inputs;
    }

    public String input(String key) {
        return inputs.get(key);
    }

    public void addProduced(String kind, String ref, String label) {
        produced.add(new AgentResult.Produced(kind, ref, label));
    }

    public List<AgentResult.Produced> produced() {
        return Collections.unmodifiableList(produced);
    }
}
