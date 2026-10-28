package de.buerokratiecopilot.agents.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    /**
     * The categories a manifest may declare. They are the industries and functions the catalogue
     * covers, not a database constraint: a manifest with an unknown category is still loaded (the
     * catalogue must not lose an agent over a typo), but the mistake is logged loudly and the
     * catalogue test fails on it.
     */
    public static final Set<String> CATEGORIES = Set.of(
            // Administratives Fundament
            "documents", "communication", "finance", "processes", "compliance", "analysis",
            "crm", "integration",
            // Branchen und Fachbereiche
            "marketing", "sales", "hr", "banking", "legal", "logistics", "support", "ecommerce",
            "production", "realestate", "hospitality", "healthcare", "it", "knowledge");

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
                    if (manifest.category() == null || !CATEGORIES.contains(manifest.category())) {
                        log.warn("agents: manifest {} declares unknown category '{}'", manifest.id(),
                                manifest.category());
                    }
                    if (manifests.putIfAbsent(manifest.id(), manifest) != null) {
                        log.error("agents: duplicate manifest id '{}' in {} ignored", manifest.id(),
                                resource);
                    }
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

    /** Categories that occur in manifests but are not in {@link #CATEGORIES}. */
    public List<String> unknownCategories() {
        Set<String> unknown = new LinkedHashSet<>();
        for (AgentManifest manifest : manifests.values()) {
            if (manifest.category() == null || !CATEGORIES.contains(manifest.category())) {
                unknown.add(String.valueOf(manifest.category()));
            }
        }
        return List.copyOf(unknown);
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
