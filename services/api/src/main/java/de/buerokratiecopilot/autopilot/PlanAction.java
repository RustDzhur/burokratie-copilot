package de.buerokratiecopilot.autopilot;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * What a step of a case plan may do. The set is closed: a plan - whoever wrote it, a person or a language model - can only
 * name these actions, and each carries how much trust it needs.
 *
 * <p>Levels (decision D-03; A/B/C of the specification are four automation levels): 1 read only, 2 prepares something that
 * changes nothing outside the workspace, 3 asks a person to decide, 4 acts outside the workspace and so needs the decision of
 * step 3 before it. {@code auto} says whether the platform may run the step itself; a step that is not automatic waits for a
 * person to do it or to confirm it.</p>
 */
public enum PlanAction {
    ANALYZE_DOCUMENTS(1, true, "ACTION", "Dokumente des Vorgangs auswerten", Set.of()),
    COMPARE_WITH_PREVIOUS(1, true, "ACTION", "Mit früheren Dokumenten vergleichen", Set.of()),
    NOTIFY_OWNER(1, true, "NOTIFICATION", "Verantwortliche informieren", Set.of()),
    CREATE_TASK(2, true, "TASK_CREATE", "Aufgabe anlegen", Set.of("taskTitle", "dueInDays")),
    ASK_USER(2, false, "ACTION", "Eine Person um Angaben oder ein Dokument bitten", Set.of("question")),
    DRAFT_LETTER(2, false, "ACTION", "Briefentwurf vorbereiten (der Text wird erst beim Erstellen geprüft)", Set.of("purpose", "recipient")),
    PREPARE_PAYMENT(2, false, "ACTION", "Zahlung vorbereiten (ohne sie auszuführen)", Set.of("amount", "reference")),
    REQUEST_APPROVAL(3, false, "APPROVAL", "Freigabe anfordern", Set.of("subjectStep")),
    SEND_LETTER(4, false, "ACTION", "Freigegebenen Brief versenden", Set.of()),
    EXECUTE_PAYMENT(4, false, "ACTION", "Freigegebene Zahlung ausführen", Set.of()),
    WAIT_FOR_REPLY(2, false, "WAIT", "Auf die Antwort warten", Set.of("waitDays"));

    private final int level;
    private final boolean auto;
    private final String stepType;
    private final String description;
    private final Set<String> allowedParams;

    PlanAction(int level, boolean auto, String stepType, String description, Set<String> allowedParams) {
        this.level = level;
        this.auto = auto;
        this.stepType = stepType;
        this.description = description;
        this.allowedParams = allowedParams;
    }

    public int level() {
        return level;
    }

    public boolean auto() {
        return auto;
    }

    /** The {@code workflow_run_steps.step_type} the action is stored as. */
    public String stepType() {
        return stepType;
    }

    public String description() {
        return description;
    }

    /** The only parameters the action takes. A letter text, an address or an account number is not among them. */
    public Set<String> allowedParams() {
        return allowedParams;
    }

    public static Optional<PlanAction> parse(String value) {
        return value == null ? Optional.empty()
                : Arrays.stream(values()).filter(action -> action.name().equals(value.strip().toUpperCase(java.util.Locale.ROOT))).findFirst();
    }
}
