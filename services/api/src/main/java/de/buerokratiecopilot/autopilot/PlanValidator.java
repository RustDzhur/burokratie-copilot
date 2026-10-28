package de.buerokratiecopilot.autopilot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides whether a proposed case plan may be saved. Only a plan that passes is stored; a plan that does not is returned to
 * its author with the list of what is wrong, and nothing of it reaches the case.
 *
 * <p>What the rules protect: a plan never lets a letter leave or a payment run without a confirmation of a person on the very
 * thing that goes out (a send step must follow an approval step that follows the draft; a payment step must follow an approval
 * that follows its preparation); a plan names only actions from the closed set; its dependencies are real and cannot form a
 * loop; and it carries no letter text, address or account number - those are created and checked later, where they are
 * validated, not smuggled in with a plan.</p>
 */
public final class PlanValidator {

    /** One step as proposed. */
    public record StepSpec(String key, String action, String title, List<String> dependsOn, boolean evidenceRequired,
                           Map<String, Object> params) {
    }

    public record Problem(String stepKey, String code, String message) {
    }

    public record Result(List<Problem> problems) {
        public boolean isValid() {
            return problems.isEmpty();
        }
    }

    static final int MAX_STEPS = 30;
    private static final Pattern KEY = Pattern.compile("[a-z0-9][a-z0-9_-]{0,39}");

    private PlanValidator() {
    }

    public static Result validate(List<StepSpec> steps) {
        List<Problem> problems = new ArrayList<>();
        if (steps == null || steps.isEmpty()) {
            problems.add(new Problem(null, "EMPTY_PLAN", "Ein Plan braucht mindestens einen Schritt"));
            return new Result(problems);
        }
        if (steps.size() > MAX_STEPS) {
            problems.add(new Problem(null, "TOO_MANY_STEPS", "Ein Plan hat höchstens " + MAX_STEPS + " Schritte"));
        }

        Map<String, StepSpec> byKey = new HashMap<>();
        for (StepSpec step : steps) {
            if (step.key() == null || !KEY.matcher(step.key()).matches()) {
                problems.add(new Problem(step.key(), "BAD_KEY", "Der Schlüssel eines Schritts besteht aus Kleinbuchstaben, Ziffern, - und _"));
                continue;
            }
            if (byKey.put(step.key(), step) != null) {
                problems.add(new Problem(step.key(), "DUPLICATE_KEY", "Der Schlüssel kommt mehrfach vor"));
            }
        }

        for (StepSpec step : steps) {
            PlanAction action = PlanAction.parse(step.action()).orElse(null);
            if (action == null) {
                problems.add(new Problem(step.key(), "UNKNOWN_ACTION", "Unbekannte Aktion: " + step.action()));
                continue;
            }
            if (step.title() == null || step.title().isBlank() || step.title().length() > 300) {
                problems.add(new Problem(step.key(), "BAD_TITLE", "Jeder Schritt braucht einen Titel (höchstens 300 Zeichen)"));
            }
            if (step.params() != null) {
                for (String name : step.params().keySet()) {
                    if (!action.allowedParams().contains(name)) {
                        problems.add(new Problem(step.key(), "PARAM_NOT_ALLOWED", "Der Parameter '" + name + "' ist für " + action
                                + " nicht erlaubt (Brieftexte, Adressen und Kontonummern gehören nicht in einen Plan)"));
                    }
                }
            }
            for (String dependency : step.dependsOn() == null ? List.<String>of() : step.dependsOn()) {
                if (dependency.equals(step.key())) {
                    problems.add(new Problem(step.key(), "SELF_DEPENDENCY", "Ein Schritt kann nicht von sich selbst abhängen"));
                }
                else if (!byKey.containsKey(dependency)) {
                    problems.add(new Problem(step.key(), "UNKNOWN_DEPENDENCY", "Abhängigkeit von einem Schritt, den es nicht gibt: " + dependency));
                }
            }
        }
        if (!problems.isEmpty()) {
            return new Result(problems);
        }

        if (hasCycle(byKey)) {
            problems.add(new Problem(null, "CYCLE", "Die Abhängigkeiten der Schritte bilden einen Kreis"));
            return new Result(problems);
        }
        for (StepSpec step : steps) {
            PlanAction action = PlanAction.parse(step.action()).orElseThrow();
            switch (action) {
                case REQUEST_APPROVAL -> requireAncestor(step, byKey, Set.of(PlanAction.DRAFT_LETTER, PlanAction.PREPARE_PAYMENT),
                        "Eine Freigabe braucht einen Brief oder eine Zahlung, über die entschieden wird", problems);
                case SEND_LETTER -> requireApprovedAncestor(step, byKey, PlanAction.DRAFT_LETTER,
                        "Ein Brief wird nur versendet, nachdem eine Freigabe zu genau diesem Briefentwurf vorliegt", problems);
                case EXECUTE_PAYMENT -> requireApprovedAncestor(step, byKey, PlanAction.PREPARE_PAYMENT,
                        "Eine Zahlung wird nur ausgeführt, nachdem eine Freigabe zu genau dieser Zahlung vorliegt", problems);
                case WAIT_FOR_REPLY -> requireAncestor(step, byKey, Set.of(PlanAction.SEND_LETTER),
                        "Auf eine Antwort wartet man erst, nachdem der Brief versendet wurde", problems);
                default -> { }
            }
        }
        return new Result(problems);
    }

    private static boolean hasCycle(Map<String, StepSpec> byKey) {
        Map<String, Integer> incoming = new HashMap<>();
        Map<String, List<String>> outgoing = new HashMap<>();
        for (StepSpec step : byKey.values()) {
            incoming.putIfAbsent(step.key(), 0);
            for (String dependency : step.dependsOn() == null ? List.<String>of() : step.dependsOn()) {
                incoming.merge(step.key(), 1, Integer::sum);
                outgoing.computeIfAbsent(dependency, k -> new ArrayList<>()).add(step.key());
            }
        }
        Deque<String> ready = new ArrayDeque<>();
        incoming.forEach((key, count) -> {
            if (count == 0) {
                ready.add(key);
            }
        });
        int visited = 0;
        while (!ready.isEmpty()) {
            String key = ready.poll();
            visited++;
            for (String next : outgoing.getOrDefault(key, List.of())) {
                if (incoming.merge(next, -1, Integer::sum) == 0) {
                    ready.add(next);
                }
            }
        }
        return visited != byKey.size();
    }

    /** All steps the given step depends on, directly or not. */
    private static Set<StepSpec> ancestors(StepSpec step, Map<String, StepSpec> byKey) {
        Set<StepSpec> found = new HashSet<>();
        Deque<StepSpec> open = new ArrayDeque<>();
        open.add(step);
        while (!open.isEmpty()) {
            StepSpec current = open.poll();
            for (String dependency : current.dependsOn() == null ? List.<String>of() : current.dependsOn()) {
                StepSpec parent = byKey.get(dependency);
                if (parent != null && found.add(parent)) {
                    open.add(parent);
                }
            }
        }
        return found;
    }

    private static void requireAncestor(StepSpec step, Map<String, StepSpec> byKey, Set<PlanAction> wanted, String message,
                                        List<Problem> problems) {
        boolean ok = ancestors(step, byKey).stream().anyMatch(a -> wanted.contains(PlanAction.parse(a.action()).orElse(null)));
        if (!ok) {
            problems.add(new Problem(step.key(), "MISSING_PRECONDITION", message));
        }
    }

    /** The step needs an approval step among its ancestors, and that approval must itself follow the given kind of step. */
    private static void requireApprovedAncestor(StepSpec step, Map<String, StepSpec> byKey, PlanAction subject, String message,
                                                List<Problem> problems) {
        boolean ok = ancestors(step, byKey).stream()
                .filter(a -> PlanAction.parse(a.action()).orElse(null) == PlanAction.REQUEST_APPROVAL)
                .anyMatch(approval -> ancestors(approval, byKey).stream()
                        .anyMatch(a -> PlanAction.parse(a.action()).orElse(null) == subject));
        if (!ok) {
            problems.add(new Problem(step.key(), "NO_APPROVAL_BEFORE_ACTION", message));
        }
    }
}
