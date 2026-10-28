package de.buerokratiecopilot.agents;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentManifest;
import de.buerokratiecopilot.agents.core.AgentRegistry;

/**
 * The runtime counterpart of {@link AgentCatalogTest}: the Spring context must supply exactly one
 * bean for every one of the 126 manifests, and no manifest may be left as a concept.
 *
 * <p>A duplicate implementation is invisible in {@link AgentRegistry} (the id-to-bean map would
 * simply keep the last bean), so the bean count is checked against the manifest count here. That is
 * the guard the merge of the implementation waves needs: {@code legal-audit-prep} existed twice.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class AgentRegistryIT {

    private static final int CATALOGUE_SIZE = 126;

    @Autowired
    private AgentRegistry registry;

    @Autowired
    private ApplicationContext context;

    @Test
    void theRegistryLoadsAll126ManifestsAndEveryOneIsImplemented() {
        assertThat(registry.all()).hasSize(CATALOGUE_SIZE);
        assertThat(registry.unimplementedIds()).isEmpty();

        List<String> ids = registry.all().stream().map(AgentManifest::id).toList();
        for (String id : ids) {
            assertThat(registry.isImplemented(id)).as(id + " has no Agent bean").isTrue();
            assertThat(registry.implementation(id)).as(id).isPresent();
        }
    }

    @Test
    void everyAgentIdIsClaimedByExactlyOneBean() {
        Collection<Agent> beans = context.getBeansOfType(Agent.class).values();
        Set<String> ids = beans.stream().map(Agent::id).collect(Collectors.toSet());

        assertThat(beans).as("one bean per agent").hasSize(CATALOGUE_SIZE);
        assertThat(ids).as("distinct agent ids").hasSize(CATALOGUE_SIZE);
    }

    @Test
    void noManifestIsStillAConcept() {
        for (AgentManifest manifest : registry.all()) {
            Object status = manifest.config().get("status");
            assertThat(status == null || "ACTIVE".equals(status))
                    .as(manifest.id() + " is not ACTIVE (status " + status + ")").isTrue();
        }
    }
}
