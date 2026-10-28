package de.buerokratiecopilot.agents.constellation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import de.buerokratiecopilot.agents.constellation.web.ConstellationsController;
import de.buerokratiecopilot.agents.core.AgentManifest;
import de.buerokratiecopilot.agents.core.AgentRegistry;
import de.buerokratiecopilot.workflow.WorkflowDefinition;
import de.buerokratiecopilot.workflow.WorkflowService;

/**
 * The translation from a constellation preset to a runnable definition, and the refusal to save one that
 * is not runnable.
 *
 * <p>The catalogue currently has no concept agents, so the refusal path cannot be produced from the real
 * presets. Testing it here is honest: the rule is exercised with the exact objects the controller receives,
 * without pretending a concept exists in the shipped catalogue.</p>
 */
class ConstellationWorkflowTest {

    private static AgentConstellation constellation() {
        return new AgentConstellation("test-chain",
                Map.of("de", "Testkette"),
                Map.of("de", "Eine Kette aus drei Schritten"),
                List.of("test"),
                List.of(
                        new AgentConstellation.Step(1, "compliance-check", "Prüfen", "prüfen", "in", "out",
                                false),
                        new AgentConstellation.Step(2, "risk-flag", "Risiken", "prüfen", "in", "out",
                                false),
                        new AgentConstellation.Step(3, "doc-datev", "Export", "exportieren", "in", "out",
                                true)),
                List.of(), null);
    }

    @Test
    @DisplayName("a constellation becomes a chain of AGENT steps with next keys and no branch")
    void aConstellationBecomesAChain() {
        WorkflowDefinition.Definition definition = ConstellationWorkflow.definitionOf(constellation());

        assertThat(definition.entryStep()).isEqualTo("step-1");
        assertThat(definition.steps()).hasSize(3);
        assertThat(definition.steps()).extracting(WorkflowDefinition.Step::key)
                .containsExactly("step-1", "step-2", "step-3");
        assertThat(definition.steps()).extracting(WorkflowDefinition.Step::type)
                .containsOnly("AGENT");
        assertThat(definition.steps()).extracting(WorkflowDefinition.Step::agentId)
                .containsExactly("compliance-check", "risk-flag", "doc-datev");
        assertThat(definition.steps().get(0).next()).isEqualTo("step-2");
        assertThat(definition.steps().get(1).next()).isEqualTo("step-3");
        assertThat(definition.steps().get(2).next()).as("the chain ends at its last step").isNull();
        assertThat(definition.steps()).allSatisfy(step ->
                assertThat(step.onFailure()).isEqualTo("PAUSE"));
        assertThat(definition.steps().get(0).inputs()).isEmpty();
        // The definition the endpoint would publish passes the structural validation.
        assertThat(WorkflowDefinition.validate(definition).isValid()).isTrue();
    }

    @Test
    @DisplayName("unknown and concept agents are reported per step")
    void unimplementedStepsAreReported() {
        List<ConstellationWorkflow.Problem> problems = ConstellationWorkflow.problems(constellation(),
                agentId -> switch (agentId) {
                    case "compliance-check" -> null;
                    case "risk-flag" -> "CONCEPT";
                    default -> "UNKNOWN";
                });

        assertThat(problems).hasSize(2);
        assertThat(problems).extracting(ConstellationWorkflow.Problem::order).containsExactly(2, 3);
        assertThat(problems).extracting(ConstellationWorkflow.Problem::reason)
                .containsExactly("CONCEPT", "UNKNOWN");
        assertThat(problems.getFirst().asMap()).containsEntry("agentId", "risk-flag");
    }

    @Test
    @DisplayName("instantiation refuses a preset with an unknown or concept agent as 409 with the list")
    void instantiateRefusesUnrunnableSteps() {
        ConstellationCatalog catalog = mock(ConstellationCatalog.class);
        AgentRegistry agents = mock(AgentRegistry.class);
        WorkflowService workflows = mock(WorkflowService.class);
        AgentConstellation broken = new AgentConstellation("broken",
                Map.of("de", "Kaputt"), Map.of("de", "Nicht ausführbar"), List.of(),
                List.of(
                        new AgentConstellation.Step(1, "compliance-check", "Prüfen", "p", "i", "o", false),
                        new AgentConstellation.Step(2, "concept-agent", "Konzept", "p", "i", "o", false),
                        new AgentConstellation.Step(3, "gibt-es-nicht", "Unbekannt", "p", "i", "o",
                                false)),
                List.of(), null);
        when(catalog.find("broken")).thenReturn(Optional.of(broken));
        when(agents.manifest("compliance-check")).thenReturn(Optional.of(manifest("compliance-check")));
        when(agents.isImplemented("compliance-check")).thenReturn(true);
        when(agents.manifest("concept-agent")).thenReturn(Optional.of(manifest("concept-agent")));
        when(agents.isImplemented("concept-agent")).thenReturn(false);
        when(agents.manifest("gibt-es-nicht")).thenReturn(Optional.empty());

        ConstellationsController controller = new ConstellationsController(catalog, agents, workflows);
        ResponseEntity<?> response = controller.instantiate("broken");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("code")).isEqualTo("CONSTELLATION_NOT_EXECUTABLE");
        assertThat((String) body.get("message")).contains("2");
        List<Map<?, ?>> steps = (List<Map<?, ?>>) body.get("steps");
        assertThat(steps).hasSize(2);
        List<String> agentIds = steps.stream().map(step -> String.valueOf(step.get("agentId")))
                .toList();
        List<String> reasons = steps.stream().map(step -> String.valueOf(step.get("reason")))
                .toList();
        assertThat(agentIds).containsExactly("concept-agent", "gibt-es-nicht");
        assertThat(reasons).containsExactly("CONCEPT", "UNKNOWN");
        // Nothing was saved: the service that writes definitions was never called.
        verifyNoInteractions(workflows);
    }

    private static AgentManifest manifest(String id) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("status", "ACTIVE");
        return new AgentManifest(id, id, "compliance", "Test", List.of(), List.of("manual"), Map.of(),
                config);
    }
}
