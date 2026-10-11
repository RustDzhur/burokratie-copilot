package de.buerokratiecopilot.workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The shape of a process definition (spec §16, F-001).
 *
 * <p>A definition is data, and this class is the only thing that says what "data" means. It is
 * deliberately small: steps with a key and a type, and the keys they lead to. Anything more expressive
 * would be a programming language, and a process that can compute is a process nobody can review.</p>
 *
 * <p>The structure is validated where it is published, not where it runs. A definition with a step that
 * leads nowhere is a fault in the definition, and finding it when a customer's invoice is halfway through
 * the process is far too late — the whole point of publishing a version is that somebody looked at it
 * first.</p>
 */
public final class WorkflowDefinition {

    /** The step types the schema allows. */
    public static final List<String> STEP_TYPES = List.of("ACTION", "CONDITION", "WAIT", "DELAY",
            "PARALLEL", "APPROVAL", "NOTIFICATION", "TASK_CREATE", "INTEGRATION_CALL", "SUB_WORKFLOW",
            "TERMINATE");

    /** One step. */
    public record Step(String key, String type, Map<String, Object> config) {

        public String next() {
            Object value = config == null ? null : config.get("next");
            return value == null ? null : String.valueOf(value);
        }

        public String onTrue() {
            Object value = config == null ? null : config.get("onTrue");
            return value == null ? null : String.valueOf(value);
        }

        public String onFalse() {
            Object value = config == null ? null : config.get("onFalse");
            return value == null ? null : String.valueOf(value);
        }

        /** How long a DELAY or WAIT step lasts, in seconds; zero when it is not a waiting step. */
        public long seconds() {
            Object value = config == null ? null : config.get("seconds");
            if (value instanceof Number number) {
                return number.longValue();
            }
            return value == null ? 0 : Long.parseLong(String.valueOf(value));
        }

        /** The event a WAIT step is waiting for, if it waits for one rather than for a time. */
        public String waitsFor() {
            Object value = config == null ? null : config.get("waitFor");
            return value == null ? null : String.valueOf(value);
        }

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("key", key);
            map.put("type", type);
            map.put("config", config == null ? Map.of() : config);
            return map;
        }
    }

    /** A process definition. */
    public record Definition(String entryStep, List<Step> steps) {

        public Step step(String key) {
            return steps.stream().filter(step -> step.key().equals(key)).findFirst().orElse(null);
        }
    }

    /** What is wrong with a definition. */
    public record Problem(String code, String message) {

        public Map<String, Object> asMap() {
            return Map.of("code", code, "message", message);
        }
    }

    /** The result of checking a definition. */
    public record Validation(List<Problem> problems) {

        public boolean isValid() {
            return problems.isEmpty();
        }

        /** One sentence naming everything that has to be fixed. */
        public String summary() {
            return problems.isEmpty() ? null
                    : problems.size() + " Problem(e): "
                            + String.join("; ", problems.stream().map(Problem::message).toList());
        }

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("valid", isValid());
            map.put("problems", problems.stream().map(Problem::asMap).toList());
            map.put("summary", summary());
            return map;
        }
    }

    private WorkflowDefinition() {
    }

    /**
     * Reads a definition from the JSON a caller supplies.
     *
     * <p>The caller is a person building a process, so the answer to a malformed definition is a list of
     * problems rather than an exception: they are going to fix it and try again.</p>
     */
    @SuppressWarnings("unchecked")
    public static Definition parse(Object json) {
        if (!(json instanceof Map<?, ?> map)) {
            return new Definition(null, List.of());
        }
        Object entry = map.get("entryStep");
        List<Step> steps = new ArrayList<>();
        Object rawSteps = map.get("steps");
        if (rawSteps instanceof List<?> list) {
            for (Object raw : list) {
                if (raw instanceof Map<?, ?> stepMap) {
                    Object key = stepMap.get("key");
                    Object type = stepMap.get("type");
                    Object config = stepMap.get("config");
                    steps.add(new Step(key == null ? null : String.valueOf(key),
                            type == null ? null : String.valueOf(type).toUpperCase(java.util.Locale.ROOT),
                            config instanceof Map<?, ?> configMap
                                    ? (Map<String, Object>) configMap : Map.of()));
                }
            }
        }
        return new Definition(entry == null ? null : String.valueOf(entry), List.copyOf(steps));
    }

    /**
     * Checks a definition.
     *
     * <p>Everything checked here is something that would otherwise fail silently in the middle of
     * somebody's process: an unknown type, a step that leads nowhere, a process that cannot start.</p>
     */
    public static Validation validate(Definition definition) {
        List<Problem> problems = new ArrayList<>();
        if (definition == null || definition.steps().isEmpty()) {
            problems.add(new Problem("NO_STEPS", "Der Prozess enthält keine Schritte"));
            return new Validation(List.copyOf(problems));
        }

        Set<String> keys = new LinkedHashSet<>();
        for (Step step : definition.steps()) {
            if (step.key() == null || step.key().isBlank()) {
                problems.add(new Problem("STEP_WITHOUT_KEY", "Ein Schritt hat keinen Schlüssel"));
                continue;
            }
            if (!keys.add(step.key())) {
                problems.add(new Problem("DUPLICATE_KEY", "Der Schlüssel '" + step.key() + "' kommt "
                        + "mehrfach vor"));
            }
            if (step.type() == null || !STEP_TYPES.contains(step.type())) {
                problems.add(new Problem("UNKNOWN_TYPE", "Der Schritt '" + step.key() + "' hat den "
                        + "unbekannten Typ '" + step.type() + "'"));
            }
        }

        String entry = definition.entryStep();
        if (entry == null || entry.isBlank()) {
            problems.add(new Problem("NO_ENTRY", "Es ist nicht angegeben, mit welchem Schritt der Prozess "
                    + "beginnt"));
        }
        else if (!keys.contains(entry)) {
            problems.add(new Problem("ENTRY_NOT_FOUND", "Der Startschritt '" + entry + "' existiert nicht"));
        }

        // Every key a step leads to has to exist; a step that leads nowhere would fail at run time.
        for (Step step : definition.steps()) {
            for (String target : List.of(step.next() == null ? "" : step.next(),
                    step.onTrue() == null ? "" : step.onTrue(),
                    step.onFalse() == null ? "" : step.onFalse())) {
                if (!target.isEmpty() && !keys.contains(target)) {
                    problems.add(new Problem("TARGET_NOT_FOUND", "Der Schritt '" + step.key() + "' verweist "
                            + "auf '" + target + "', den es nicht gibt"));
                }
            }
            if ("CONDITION".equals(step.type()) && (step.onTrue() == null || step.onFalse() == null)) {
                problems.add(new Problem("CONDITION_WITHOUT_BRANCHES", "Die Bedingung '" + step.key()
                        + "' braucht beide Zweige"));
            }
            if (("DELAY".equals(step.type()) || "WAIT".equals(step.type())) && step.seconds() < 0) {
                problems.add(new Problem("NEGATIVE_WAIT", "Der Schritt '" + step.key() + "' wartet eine "
                        + "negative Zeit"));
            }
        }

        return new Validation(List.copyOf(problems));
    }

    /** A minimal definition with one terminating step, for a caller that wants a starting point. */
    public static Definition trivial(String stepKey) {
        return new Definition(stepKey, List.of(new Step(stepKey, "TERMINATE", Map.of())));
    }
}
