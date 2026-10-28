package de.buerokratiecopilot.agents.core;

import java.util.UUID;

import de.buerokratiecopilot.tasks.domain.Task;

/** Shared fixtures for the agent unit tests: no database, no storage, no request context. */
public final class AgentTestFixtures {

    private AgentTestFixtures() {
    }

    public static AgentArtifactStore.Artifact artifact(String kind) {
        return new AgentArtifactStore.Artifact(UUID.randomUUID(), kind, kind + ":source",
                "tenants/storage-key", "0".repeat(64), 42, false);
    }

    public static Task task(UUID tenantId, String title) {
        return new Task(UUID.randomUUID(), tenantId, title);
    }
}
