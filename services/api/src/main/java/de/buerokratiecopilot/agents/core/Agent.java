package de.buerokratiecopilot.agents.core;

/**
 * One catalogue agent. The {@link #id()} must equal the manifest {@code id}; the registry
 * joins manifests (from classpath) with beans (from the Spring context) by that id.
 */
public interface Agent {

    String id();

    AgentResult run(AgentContext context);
}
