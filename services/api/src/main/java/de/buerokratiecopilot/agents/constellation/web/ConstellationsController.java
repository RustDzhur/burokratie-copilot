package de.buerokratiecopilot.agents.constellation.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.agents.constellation.AgentConstellation;
import de.buerokratiecopilot.agents.constellation.ConstellationCatalog;
import de.buerokratiecopilot.agents.constellation.ConstellationWorkflow;
import de.buerokratiecopilot.agents.core.AgentRegistry;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.workflow.WorkflowDefinition;
import de.buerokratiecopilot.workflow.WorkflowService;

/**
 * Constellation presets: read a workflow, inspect its steps, and save it as a runnable process.
 *
 * <p><strong>Instantiation saves the chain.</strong> The engine now has an {@code AGENT} step, so a preset
 * becomes a published workflow version: one agent call per preset step, each one leading to the next. That
 * is what makes a constellation more than a picture — the run API can execute it, and the engine records
 * every agent run in the process history.</p>
 *
 * <p><strong>A preset whose agent is a concept is refused, not saved.</strong> Writing a step the engine
 * cannot execute would produce a process that fails halfway through, and the person who clicked
 * "instantiate" would have no way to see that coming. The refusal names every such step.</p>
 */
@RestController
@RequestMapping("/api/v1/constellations")
@Tag(name = "Constellations", description = "Workflow presets across catalogue agents")
public class ConstellationsController {

    private static final String VIEW = "hasAnyAuthority('agents.view','constellations.view')";
    private static final String INSTANTIATE =
            "hasAnyAuthority('agents.run','constellations.instantiate')";

    private final ConstellationCatalog catalog;
    private final AgentRegistry agents;
    private final WorkflowService workflows;

    public ConstellationsController(ConstellationCatalog catalog, AgentRegistry agents,
                                    WorkflowService workflows) {
        this.catalog = catalog;
        this.agents = agents;
        this.workflows = workflows;
    }

    public record Summary(String id, Map<String, String> name, Map<String, String> description,
                          List<String> industries, int steps, int implementedSteps,
                          int conceptSteps,
                          AgentConstellation.EstimatedTimeSaved estimatedTimeSaved) {
    }

    public record StepView(int order, String agentId, String agentName, boolean implemented,
                           String title, String purpose, String input, String output,
                           boolean optional) {
    }

    public record Detail(String id, Map<String, String> name, Map<String, String> description,
                         List<String> industries, List<StepView> steps,
                         List<Map<String, String>> outputs,
                         AgentConstellation.EstimatedTimeSaved estimatedTimeSaved,
                         int implementedSteps, int conceptSteps, List<String> unknownAgents) {
    }

    /** One saved workflow step, so the caller can see the chain without a second request. */
    public record SavedStep(String key, String type, String agentId, String next) {
    }

    /** The workflow a constellation was saved as. */
    public record Instantiated(String constellationId, String workflowId, String versionId, int version,
                               boolean saved, String entryStep, int stepCount, int savedSteps,
                               List<SavedStep> steps, List<String> warnings) {
    }

    @GetMapping
    @PreAuthorize(VIEW)
    @Operation(summary = "All workflow presets with their implementation coverage")
    public List<Summary> list() {
        return catalog.all().stream().map(this::summary).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize(VIEW)
    @Operation(summary = "One preset with resolved agent names and coverage")
    public ResponseEntity<Detail> one(@PathVariable String id) {
        Optional<AgentConstellation> found = catalog.find(id);
        if (found.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        AgentConstellation constellation = found.get();
        List<StepView> steps = stepViews(constellation);
        int implemented = (int) steps.stream().filter(StepView::implemented).count();
        return ResponseEntity.ok(new Detail(constellation.id(), constellation.name(),
                constellation.description(), constellation.industries(), steps,
                constellation.outputs(), constellation.estimatedTimeSaved(), implemented,
                steps.size() - implemented, catalog.unknownAgents(id)));
    }

    /**
     * Saves the preset as a published workflow version and returns its id.
     *
     * <p>A second instantiation publishes a new version of the same definition instead of a second process:
     * runs that already started keep the version they started on.</p>
     */
    @PostMapping("/{id}/instantiate")
    @PreAuthorize(INSTANTIATE)
    @Operation(summary = "Saves a preset as a runnable workflow (steps of type AGENT)")
    public ResponseEntity<?> instantiate(@PathVariable String id) {
        Optional<AgentConstellation> found = catalog.find(id);
        if (found.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        AgentConstellation constellation = found.get();

        List<ConstellationWorkflow.Problem> problems = ConstellationWorkflow.problems(constellation,
                agentProblem());
        if (!problems.isEmpty()) {
            List<Map<String, Object>> steps = problems.stream()
                    .map(ConstellationWorkflow.Problem::asMap).toList();
            String message = "Die Konstellation '" + id + "' ist nicht ausführbar: "
                    + problems.size() + " Schritt(e) rufen Agenten auf, die es nicht gibt oder die nur "
                    + "Konzepte sind";
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", "CONSTELLATION_NOT_EXECUTABLE");
            body.put("message", message);
            body.put("steps", steps);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        }

        WorkflowDefinition.Definition definition = ConstellationWorkflow.definitionOf(constellation);
        UUID tenantId = RequestContext.tenantId().orElseThrow();
        UUID actor = CurrentUser.required();
        WorkflowService.Workflow workflow = workflows.define(tenantId,
                ConstellationWorkflow.code(constellation),
                constellation.name().getOrDefault("de", constellation.id()),
                constellation.description().get("de"), "MANUAL",
                Map.of("type", "CONSTELLATION", "constellationId", constellation.id()), actor);
        WorkflowService.Version version = workflows.publish(tenantId, workflow.definitionId(),
                definition.asMap(), Map.of(), "Instanziiert aus der Konstellation '" + id + "'", actor);

        List<SavedStep> steps = definition.steps().stream()
                .map(step -> new SavedStep(step.key(), step.type(), step.agentId(), step.next()))
                .toList();
        List<String> warnings = new ArrayList<>();
        warnings.add("Die Schritte tragen keine festen Eingaben: Ein Lauf pausiert beim ersten Agenten, "
                + "der Daten braucht, und wird mit RETRY samt Eingaben fortgesetzt.");
        return ResponseEntity.ok(new Instantiated(constellation.id(),
                workflow.definitionId().toString(), version.versionId().toString(), version.version(),
                true, definition.entryStep(), steps.size(), steps.size(), steps, List.copyOf(warnings)));
    }

    private Function<String, String> agentProblem() {
        return agentId -> {
            if (agentId == null || agentId.isBlank()) {
                return "UNKNOWN";
            }
            if (agents.manifest(agentId).isEmpty()) {
                return "UNKNOWN";
            }
            return agents.isImplemented(agentId) ? null : "CONCEPT";
        };
    }

    private Summary summary(AgentConstellation constellation) {
        int implemented = ConstellationCatalog.implementedSteps(constellation, agents);
        return new Summary(constellation.id(), constellation.name(), constellation.description(),
                constellation.industries(), constellation.steps().size(), implemented,
                constellation.steps().size() - implemented, constellation.estimatedTimeSaved());
    }

    private List<StepView> stepViews(AgentConstellation constellation) {
        return constellation.steps().stream()
                .map(step -> new StepView(step.order(), step.agentId(),
                        ConstellationCatalog.agentName(agents, step.agentId()),
                        agents.isImplemented(step.agentId()), step.title(), step.purpose(),
                        step.input(), step.output(), step.optionalStep()))
                .toList();
    }
}
