package de.buerokratiecopilot.agents.core;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Declarative part of an agent: identity, what it promises to do, when it runs and the
 * rules it follows. Immutable and shipped with the application (classpath JSON), so the
 * catalogue is code-reviewed like the implementations.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentManifest(
        String id,
        String name,
        String category,
        String description,
        List<String> functions,
        List<String> triggers,
        Map<String, String> inputs,
        Map<String, Object> config) {

    public AgentManifest {
        functions = functions == null ? List.of() : functions;
        triggers = triggers == null ? List.of() : triggers;
        inputs = inputs == null ? Map.of() : inputs;
        config = config == null ? Map.of() : config;
    }

    public String configString(String key, String fallback) {
        Object value = config.get(key);
        return value == null ? fallback : String.valueOf(value);
    }
}
