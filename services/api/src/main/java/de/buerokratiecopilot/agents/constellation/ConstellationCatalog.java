package de.buerokratiecopilot.agents.constellation;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.core.AgentManifest;
import de.buerokratiecopilot.agents.core.AgentRegistry;

/**
 * Loads the constellation presets from {@code classpath*:agent-constellations/*.json} and checks
 * them against the agent catalogue.
 *
 * <p>Loading is fail-soft per file: a broken preset is logged and skipped, the rest of the
 * catalogue stays usable. A preset that references an unknown agent is still shown, but with a
 * warning — hiding it would hide the mistake.</p>
 */
@Component
public class ConstellationCatalog {

    private static final Logger log = LoggerFactory.getLogger(ConstellationCatalog.class);
    private static final Pattern ID_PATTERN = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    private final AgentRegistry agents;
    private final Map<String, AgentConstellation> constellations = new LinkedHashMap<>();
    private final Map<String, List<String>> unknownAgents = new LinkedHashMap<>();

    public ConstellationCatalog(AgentRegistry agents, ObjectMapper mapper) {
        this.agents = agents;
        load(mapper);
    }

    private void load(ObjectMapper mapper) {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:agent-constellations/*.json");
            List<Resource> ordered = new ArrayList<>(List.of(resources));
            ordered.sort(Comparator.comparing(resource -> String.valueOf(resource.getFilename())));
            for (Resource resource : ordered) {
                try (InputStream in = resource.getInputStream()) {
                    AgentConstellation constellation = mapper.readValue(in, AgentConstellation.class);
                    String problem = validate(constellation);
                    if (problem != null) {
                        log.error("constellations: {} ignored: {}", resource.getFilename(), problem);
                        continue;
                    }
                    if (constellations.putIfAbsent(constellation.id(), constellation) != null) {
                        log.error("constellations: duplicate id '{}' in {} ignored",
                                constellation.id(), resource.getFilename());
                        continue;
                    }
                    List<String> unknown = new ArrayList<>();
                    for (AgentConstellation.Step step : constellation.steps()) {
                        if (!agents.isImplemented(step.agentId())
                                && agents.manifest(step.agentId()).isEmpty()) {
                            unknown.add(step.agentId());
                        }
                    }
                    if (!unknown.isEmpty()) {
                        log.warn("constellations: {} references unknown agents: {}", constellation.id(),
                                unknown);
                        unknownAgents.put(constellation.id(), List.copyOf(unknown));
                    }
                } catch (IOException e) {
                    log.error("constellations: cannot read {}", resource, e);
                }
            }
        } catch (IOException e) {
            log.error("constellations: cannot scan classpath", e);
        }
    }

    /** Structural validation; returns a readable problem or {@code null}. */
    static String validate(AgentConstellation constellation) {
        if (constellation == null || constellation.id() == null
                || constellation.id().isBlank()) {
            return "id is missing";
        }
        if (!ID_PATTERN.matcher(constellation.id()).matches()) {
            return "id is not kebab-case: " + constellation.id();
        }
        if (constellation.name().get("de") == null || constellation.name().get("de").isBlank()) {
            return "German name is missing";
        }
        if (constellation.description().get("de") == null
                || constellation.description().get("de").isBlank()) {
            return "German description is missing";
        }
        if (constellation.steps().isEmpty()) {
            return "no steps";
        }
        Set<Integer> orders = new LinkedHashSet<>();
        for (AgentConstellation.Step step : constellation.steps()) {
            if (step.order() < 1 || !orders.add(step.order())) {
                return "duplicate or invalid step order " + step.order();
            }
            if (step.agentId() == null || step.agentId().isBlank()) {
                return "step " + step.order() + " has no agentId";
            }
            if (step.title() == null || step.title().isBlank()) {
                return "step " + step.order() + " has no title";
            }
        }
        for (int expected = 1; expected <= constellation.steps().size(); expected++) {
            if (!orders.contains(expected)) {
                return "step order has a gap at " + expected;
            }
        }
        return null;
    }

    /** All presets, sorted by id for a stable API. */
    public List<AgentConstellation> all() {
        return constellations.values().stream()
                .sorted(Comparator.comparing(AgentConstellation::id))
                .toList();
    }

    public Optional<AgentConstellation> find(String id) {
        return Optional.ofNullable(constellations.get(id));
    }

    /** Agent ids a preset references that the catalogue does not know. */
    public List<String> unknownAgents(String constellationId) {
        return unknownAgents.getOrDefault(constellationId, List.of());
    }

    /** Steps whose agent exists as a manifest; the number says how much of the preset is buildable. */
    public static int implementedSteps(AgentConstellation constellation, AgentRegistry agents) {
        int implemented = 0;
        for (AgentConstellation.Step step : constellation.steps()) {
            if (agents.isImplemented(step.agentId())) {
                implemented++;
            }
        }
        return implemented;
    }

    /** Resolved name of an agent for display, falling back to the id. */
    public static String agentName(AgentRegistry agents, String agentId) {
        return agents.manifest(agentId).map(AgentManifest::name).orElse(agentId);
    }

    /** All agent ids referenced by the presets, for validation and coverage reports. */
    public Set<String> referencedAgentIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (AgentConstellation constellation : constellations.values()) {
            for (AgentConstellation.Step step : constellation.steps()) {
                ids.add(step.agentId());
            }
        }
        return ids;
    }
}
