package de.buerokratiecopilot.agents.constellation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import de.buerokratiecopilot.workflow.WorkflowDefinition;
import de.buerokratiecopilot.workflow.WorkflowDefinition.Definition;
import de.buerokratiecopilot.workflow.WorkflowDefinition.Step;

/**
 * Turns a constellation preset into a workflow definition the engine can run.
 *
 * <p>A constellation is an ordered sequence of catalogue agents with no branches. The definition mirrors
 * that exactly: one {@code AGENT} step per preset step, the keys {@code step-<order>} in the same order,
 * and each step's {@code next} pointing at the following one. The last step has no {@code next}, so the
 * engine completes the run when it is done — no artificial TERMINATE step whose only purpose would be to
 * satisfy a validator.</p>
 *
 * <p>The translation is a pure function and lives here rather than in the controller so the two questions
 * that matter can be tested without a database: is every step executable, and does the chain have the
 * shape the preset describes.</p>
 */
public final class ConstellationWorkflow {

    private ConstellationWorkflow() {
    }

    /** A step that cannot be saved because its agent is missing or is still a concept. */
    public record Problem(int order, String agentId, String title, String reason) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("order", order);
            map.put("agentId", agentId);
            map.put("title", title);
            map.put("reason", reason);
            return map;
        }
    }

    /** The definition code a constellation is stored under; stable, so re-instantiating versions it. */
    public static String code(AgentConstellation constellation) {
        return "constellation-" + constellation.id();
    }

    /**
     * The steps whose agent cannot be called.
     *
     * @param agentProblem given an agent id, returns {@code UNKNOWN} / {@code CONCEPT} or another readable
     *                     reason, and {@code null} when the agent is executable.
     */
    public static List<Problem> problems(AgentConstellation constellation,
                                         Function<String, String> agentProblem) {
        List<Problem> problems = new ArrayList<>();
        for (AgentConstellation.Step step : constellation.steps()) {
            String reason = agentProblem.apply(step.agentId());
            if (reason != null) {
                problems.add(new Problem(step.order(), step.agentId(), step.title(), reason));
            }
        }
        return List.copyOf(problems);
    }

    /**
     * The chain as a definition.
     *
     * <p>The preset's {@code input} and {@code output} texts are documentation, not values: a step is saved
     * without fixed inputs and pauses when its agent needs data, at which point a person supplies the values
     * with a RETRY. Inventing inputs from a description would be the kind of guess that looks like
     * automation until somebody checks the result.</p>
     */
    public static Definition definitionOf(AgentConstellation constellation) {
        List<AgentConstellation.Step> source = constellation.steps();
        List<Step> steps = new ArrayList<>(source.size());
        for (int i = 0; i < source.size(); i++) {
            AgentConstellation.Step presetStep = source.get(i);
            String key = keyOf(presetStep);
            String next = i + 1 < source.size() ? keyOf(source.get(i + 1)) : null;

            Map<String, Object> config = new LinkedHashMap<>();
            config.put("agentId", presetStep.agentId());
            config.put("inputs", Map.of());
            config.put("onFailure", "PAUSE");
            if (next != null) {
                config.put("next", next);
            }
            steps.add(new Step(key, "AGENT", Map.copyOf(config)));
        }
        String entry = source.isEmpty() ? null : keyOf(source.getFirst());
        return new Definition(entry, List.copyOf(steps));
    }

    private static String keyOf(AgentConstellation.Step step) {
        return "step-" + step.order();
    }
}
