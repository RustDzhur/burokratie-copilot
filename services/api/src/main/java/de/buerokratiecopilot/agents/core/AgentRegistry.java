package de.buerokratiecopilot.agents.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The agent catalogue: every manifest under {@code agents/**\/\*.json} on the classpath,
 * joined with the Spring bean that implements it (by {@link Agent#id()}).
 *
 * <p>Loading is fail-soft per file: one malformed manifest must not take the platform down,
 * but it is logged loudly so it gets fixed.</p>
 */
@Component
public class AgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentRegistry.class);

    private final Map<String, AgentManifest> manifests = new LinkedHashMap<>();
    private final Map<String, Agent> implementations;

    public AgentRegistry(ApplicationContext context, ObjectMapper objectMapper) {
        Map<String, Agent> beans = new LinkedHashMap<>();
        for (Agent agent : context.getBeansOfType(Agent.class).values()) {
            beans.put(agent.id(), agent);
        }
        this.implementations = Map.copyOf(beans);
        loadManifests(objectMapper);
    }

    private void loadManifests(ObjectMapper mapper) {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:agents/**/*.json");
            for (Resource resource : resources) {
                try (InputStream in = resource.getInputStream()) {
                    AgentManifest manifest = mapper.readValue(in, AgentManifest.class);
                    if (manifest.id() == null || manifest.id().isBlank()) {
                        log.error("agents: manifest without id ignored: {}", resource);
                        continue;
                    }
                    manifests.put(manifest.id(), manifest);
                } catch (IOException e) {
                    log.error("agents: cannot read manifest {}", resource, e);
                }
            }
        } catch (IOException e) {
            log.error("agents: cannot scan classpath for manifests", e);
        }
    }

    public List<AgentManifest> all() {
        return List.copyOf(manifests.values());
    }

    public Optional<AgentManifest> manifest(String id) {
        return Optional.ofNullable(manifests.get(id));
    }

    public Optional<Agent> implementation(String id) {
        return Optional.ofNullable(implementations.get(id));
    }

    public boolean isImplemented(String id) {
        return implementations.containsKey(id);
    }

    public List<String> unimplementedIds() {
        List<String> missing = new ArrayList<>();
        for (String id : manifests.keySet()) {
            if (!implementations.containsKey(id)) {
                missing.add(id);
            }
        }
        return missing;
    }
}
