package de.buerokratiecopilot.agents.constellation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.agents.core.AgentRegistry;

/**
 * The runtime counterpart of {@link ConstellationCatalogTest}: it asks the Spring context, not a
 * checked-in list, whether every step of every preset has a bean. A manifest that says ACTIVE but
 * has no implementation cannot hide here.
 */
@SpringBootTest
@ActiveProfiles("test")
class ConstellationsImplementedIT {

    @Autowired
    private AgentRegistry registry;

    @Autowired
    private ConstellationCatalog catalog;

    @Test
    void everyConstellationStepHasAnImplementation() {
        assertThat(catalog.all()).hasSize(30);
        for (var constellation : catalog.all()) {
            for (var step : constellation.steps()) {
                assertThat(registry.isImplemented(step.agentId()))
                        .as(constellation.id() + " step " + step.order() + ": " + step.agentId()
                                + " has no Agent bean")
                        .isTrue();
            }
        }
    }

    @Test
    void theCatalogueContainsNoConceptForAConstellationStep() {
        for (var constellation : catalog.all()) {
            for (var step : constellation.steps()) {
                var manifest = registry.manifest(step.agentId()).orElseThrow();
                Object status = manifest.config().get("status");
                if (status != null) {
                    assertThat(status).as(constellation.id() + " / " + step.agentId())
                            .isEqualTo("ACTIVE");
                }
            }
        }
    }
}
