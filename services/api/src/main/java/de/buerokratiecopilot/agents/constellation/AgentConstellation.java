package de.buerokratiecopilot.agents.constellation;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One workflow preset ("constellation"): a named, ordered sequence of catalogue agents that solves
 * one recurring task. It is data, reviewed in the repository like a manifest, and it references
 * existing agent ids only — a preset that invents an agent is a promise nobody implements.
 *
 * @param name                localised display name ({@code de}, {@code ru})
 * @param description         localised description: task, business, outcome
 * @param industries          industry tags ({@code handwerk}, {@code ecommerce}, …)
 * @param estimatedTimeSaved  expected saving, with a machine value and a localised label
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentConstellation(
        String id,
        Map<String, String> name,
        Map<String, String> description,
        List<String> industries,
        List<Step> steps,
        List<Map<String, String>> outputs,
        EstimatedTimeSaved estimatedTimeSaved) {

    public AgentConstellation {
        name = name == null ? Map.of() : name;
        description = description == null ? Map.of() : description;
        industries = industries == null ? List.of() : industries;
        steps = steps == null ? List.of() : steps;
        outputs = outputs == null ? List.of() : outputs;
    }

    /** One step of the preset. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Step(int order, String agentId, String title, String purpose, String input,
                       String output, Boolean optional) {

        public boolean optionalStep() {
            return Boolean.TRUE.equals(optional);
        }
    }

    /** Time saved per week: a number for sorting, a label for people. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EstimatedTimeSaved(Integer minutesPerWeek, Map<String, String> label) {

        public EstimatedTimeSaved {
            label = label == null ? Map.of() : label;
        }
    }
}
