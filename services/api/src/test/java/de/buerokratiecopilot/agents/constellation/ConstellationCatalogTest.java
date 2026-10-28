package de.buerokratiecopilot.agents.constellation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.core.AgentManifest;
import de.buerokratiecopilot.agents.core.AgentRegistry;

/**
 * Guards the constellation contract: 24–30 presets, unique ids, contiguous step orders, localised
 * names and descriptions, and every step pointing at an agent that really exists in the catalogue.
 */
class ConstellationCatalogTest {

    private static List<AgentConstellation> constellations;
    private static Set<String> agentIds;
    private static Map<String, AgentManifest> manifestsById;

    @BeforeAll
    static void load() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        List<AgentConstellation> loaded = new ArrayList<>();
        for (Resource resource : resolver.getResources("classpath*:agent-constellations/*.json")) {
            try (InputStream in = resource.getInputStream()) {
                loaded.add(mapper.readValue(in, AgentConstellation.class));
            }
        }
        constellations = List.copyOf(loaded);

        Set<String> ids = new HashSet<>();
        Map<String, AgentManifest> manifests = new LinkedHashMap<>();
        for (Resource resource : resolver.getResources("classpath*:agents/**/*.json")) {
            try (InputStream in = resource.getInputStream()) {
                AgentManifest manifest = mapper.readValue(in, AgentManifest.class);
                ids.add(manifest.id());
                manifests.put(manifest.id(), manifest);
            }
        }
        agentIds = Set.copyOf(ids);
        manifestsById = Map.copyOf(manifests);
    }

    @Test
    void thereAreBetweenTwentyFourAndThirtyPresets() {
        assertThat(constellations).hasSizeBetween(24, 30);
        assertThat(constellations).hasSize(30);
    }

    @Test
    void idsAreUniqueKebabCaseAndStructurallyValid() {
        Set<String> ids = new HashSet<>();
        for (AgentConstellation constellation : constellations) {
            assertThat(ids.add(constellation.id())).as("duplicate id " + constellation.id()).isTrue();
            assertThat(constellation.id()).matches("^[a-z0-9]+(-[a-z0-9]+)*$");
            assertThat(ConstellationCatalog.validate(constellation)).as(constellation.id()).isNull();
        }
    }

    @Test
    void everyStepReferencesAnExistingAgent() {
        for (AgentConstellation constellation : constellations) {
            for (AgentConstellation.Step step : constellation.steps()) {
                assertThat(agentIds).as(constellation.id() + " step " + step.order())
                        .contains(step.agentId());
            }
        }
    }

    @Test
    void stepOrdersAreContiguousAndStartAtOne() {
        for (AgentConstellation constellation : constellations) {
            List<Integer> orders = constellation.steps().stream()
                    .map(AgentConstellation.Step::order).toList();
            assertThat(orders).as(constellation.id()).hasSize(constellation.steps().size());
            for (int i = 0; i < orders.size(); i++) {
                assertThat(orders.get(i)).as(constellation.id() + " step " + i)
                        .isEqualTo(i + 1);
            }
        }
    }

    @Test
    void everyPresetIsLocalisedAndSaysWhatComesOut() {
        for (AgentConstellation constellation : constellations) {
            assertThat(constellation.name()).as(constellation.id() + " name")
                    .containsKeys("de", "ru");
            assertThat(constellation.description()).as(constellation.id() + " description")
                    .containsKeys("de", "ru");
            assertThat(constellation.description().get("de")).as(constellation.id())
                    .hasSizeGreaterThanOrEqualTo(120);
            assertThat(constellation.industries()).as(constellation.id() + " industries")
                    .isNotEmpty();
            assertThat(constellation.outputs()).as(constellation.id() + " outputs").isNotEmpty();
            assertThat(constellation.estimatedTimeSaved()).as(constellation.id() + " time")
                    .isNotNull();
            assertThat(constellation.estimatedTimeSaved().minutesPerWeek())
                    .as(constellation.id() + " minutes").isPositive();
        }
    }

    @Test
    void thePresetsCoverTheRequestedBusinessTasks() {
        Set<String> ids = new HashSet<>();
        for (AgentConstellation constellation : constellations) {
            ids.add(constellation.id());
        }
        assertThat(ids).contains("inbox-triage", "datev-pipeline", "dunning-cycle",
                "customer-onboarding", "recruiting-pipeline", "content-plan", "returns-handling",
                "supply-chain", "kyc-sanctions", "gdpr-request", "monthly-kpi", "contract-control",
                "warehouse-count", "ecommerce-orders", "handwerk-scheduling", "warranty-case",
                "nps-loop", "employee-training", "incident-response", "bank-reconciliation",
                "budgeting", "customs-clearance", "reservations", "shift-planning", "audit-prep");
    }

    @Test
    void aGapInTheStepOrderIsRejected() {
        AgentConstellation broken = new AgentConstellation("broken-preset",
                Map.of("de", "Kaputt", "ru", "Сломано"),
                Map.of("de", "Beschreibung", "ru", "Описание"), List.of("handwerk"),
                List.of(new AgentConstellation.Step(1, "doc-detect", "Start", "Zweck", "In", "Out",
                                null),
                        new AgentConstellation.Step(3, "doc-ocr", "Sprung", "Zweck", "In", "Out",
                                null)),
                List.of(Map.of("de", "Ergebnis", "ru", "Результат")),
                new AgentConstellation.EstimatedTimeSaved(10, Map.of("de", "10 Min.", "ru", "10 мин.")));

        assertThat(ConstellationCatalog.validate(broken)).contains("gap");
    }

    @Test
    void everyStepOfEveryPresetIsImplemented() {
        for (AgentConstellation constellation : constellations) {
            for (AgentConstellation.Step step : constellation.steps()) {
                AgentManifest manifest = manifestsById.get(step.agentId());
                assertThat(manifest).as(constellation.id() + " step " + step.order()
                        + " references unknown agent " + step.agentId()).isNotNull();
                Object status = manifest.config().get("status");
                // Legacy manifests (documents, finance, ...) predate the status field; they are
                // all implemented. New industry manifests must be ACTIVE to pass.
                if (status != null) {
                    assertThat(status).as(constellation.id() + " step " + step.order() + " ("
                            + step.agentId() + ") is still a concept").isEqualTo("ACTIVE");
                }
            }
        }
    }

    @Test
    void everyAgentThePresetsNeedIsCoveredByAtLeastOnePreset() {
        Set<String> referenced = new HashSet<>();
        for (AgentConstellation constellation : constellations) {
            for (AgentConstellation.Step step : constellation.steps()) {
                referenced.add(step.agentId());
            }
        }
        assertThat(referenced).contains("doc-detect", "dunning", "crm-deal", "hr-onboarding",
                "knowledge-kpi-report", "support-ticket", "legal-gdpr-request",
                "banking-reconcile", "logistics-customs", "hospitality-shift-plan");
    }
}
