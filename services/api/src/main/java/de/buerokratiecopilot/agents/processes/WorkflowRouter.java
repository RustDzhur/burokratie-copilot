package de.buerokratiecopilot.agents.processes;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Pure decision logic of {@code workflow-route}: given the facts of a case and its workflow runs,
 * which step comes next and who receives the case.
 *
 * <p>Deliberately a pure function like {@code AssignmentEngine}: every input is a value, every
 * outcome is written down, so the cases that matter can be tested exactly — an overdue waiting run is
 * resumed, a running run is advanced, a fresh case without a run gets its preset process started, and
 * a closed case gets nothing at all.</p>
 */
public final class WorkflowRouter {

    private WorkflowRouter() {
    }

    /** The state of one workflow run, as far as routing needs it. */
    public record RunFacts(String runId, String status, String currentStepKey, String waitingForEvent,
                           Instant waitUntil) {

        public static RunFacts of(String runId, String status, String currentStepKey,
                                  String waitingForEvent, Instant waitUntil) {
            return new RunFacts(runId, status == null ? "" : status.toUpperCase(Locale.ROOT),
                    currentStepKey, waitingForEvent, waitUntil);
        }
    }

    /** The state of a case, as far as routing needs it. */
    public record CaseFacts(String caseId, String status, String caseTypeCode,
                            boolean hasPendingAssignmentTask, boolean hasOverdueDeadline,
                            boolean hasUnverifiedDeadline) {
    }

    /** What the router decided: an action, optionally a target run, the handover and an explanation. */
    public record Route(String action, String targetRunId, String handover, String nextStep,
                        String explanation) {

        public static Route of(String action, String targetRunId, String handover, String nextStep,
                               String explanation) {
            return new Route(action, targetRunId, handover, nextStep, explanation);
        }

        /** Whether the route demands a real, executable side effect. */
        public boolean isExecutable() {
            return switch (action) {
                case "START_PROCESS", "ADVANCE_RUN", "RESUME_WAIT", "ASSIGN_PENDING_TASK" -> true;
                default -> false;
            };
        }

        public java.util.Map<String, String> meta() {
            return java.util.Map.of(
                    "action", action,
                    "targetRunId", targetRunId == null ? "" : targetRunId,
                    "handover", handover == null ? "" : handover,
                    "nextStep", nextStep == null ? "" : nextStep,
                    "explanation", explanation == null ? "" : explanation);
        }
    }

    public static final String ACTION_START_PROCESS = "START_PROCESS";
    public static final String ACTION_ADVANCE_RUN = "ADVANCE_RUN";
    public static final String ACTION_RESUME_WAIT = "RESUME_WAIT";
    public static final String ACTION_WAIT_EVENT = "WAIT_EVENT";
    public static final String ACTION_WAIT_TIME = "WAIT_TIME";
    public static final String ACTION_AWAIT_APPROVAL = "AWAIT_APPROVAL";
    public static final String ACTION_ASSIGN_PENDING_TASK = "ASSIGN_PENDING_TASK";
    public static final String ACTION_ESCALATE_OVERDUE_DEADLINE = "ESCALATE_OVERDUE_DEADLINE";
    public static final String ACTION_VERIFY_DEADLINE = "VERIFY_DEADLINE";
    public static final String ACTION_IDLE = "IDLE";
    public static final String ACTION_CLOSED = "CLOSED";

    /**
     * The single routing rule for one case and its runs, evaluated in a fixed priority order:
     * a run whose waiting time has passed is resumed; a run waiting for a person goes to that person;
     * a running run is advanced; a run waiting for an event names the event it waits for. Only when no
     * run is active at all does the case state decide: a fresh case starts its preset process, an
     * unassigned task gets assigned, an overdue deadline is escalated.
     */
    public static Route route(CaseFacts facts, List<RunFacts> runs, Instant now) {
        if (facts == null) {
            return Route.of(ACTION_IDLE, null, null, null,
                    "Keine Vorgangsdaten vorhanden — es kann nichts gesteuert werden");
        }
        boolean closed = "COMPLETED".equals(facts.status()) || "CLOSED".equals(facts.status());
        if (closed && (runs == null || runs.isEmpty())) {
            return Route.of(ACTION_CLOSED, null, null, null,
                    "Der Vorgang ist abgeschlossen — es gibt keinen nächsten Schritt");
        }

        Instant at = now == null ? Instant.now() : now;

        RunFacts overdueWait = null;
        RunFacts approval = null;
        RunFacts running = null;
        RunFacts waitingTime = null;
        RunFacts waitingEvent = null;
        for (RunFacts run : runs == null ? List.<RunFacts>of() : runs) {
            switch (run.status()) {
                case "WAITING" -> {
                    if (run.waitingForEvent() != null && !run.waitingForEvent().isBlank()) {
                        if (waitingEvent == null) {
                            waitingEvent = run;
                        }
                    }
                    else if (run.waitUntil() != null) {
                        if (!run.waitUntil().isAfter(at)) {
                            overdueWait = run;
                        }
                        else if (waitingTime == null) {
                            waitingTime = run;
                        }
                    }
                }
                case "WAITING_APPROVAL" -> {
                    if (approval == null) {
                        approval = run;
                    }
                }
                case "RUNNING", "PENDING" -> {
                    if (running == null) {
                        running = run;
                    }
                }
                default -> {
                    // COMPLETED, CANCELLED, FAILED — nothing to route here.
                }
            }
        }

        if (overdueWait != null) {
            return Route.of(ACTION_RESUME_WAIT, overdueWait.runId(), null,
                    overdueWait.currentStepKey(),
                    "Die Wartezeit des Laufs ist abgelaufen — der Lauf wird fortgesetzt");
        }
        if (approval != null) {
            return Route.of(ACTION_AWAIT_APPROVAL, approval.runId(), "approver", approval.currentStepKey(),
                    "Der Prozess wartet auf eine Freigabe; die Übergabe geht an die freigabeberechtigte Person");
        }
        if (running != null) {
            return Route.of(ACTION_ADVANCE_RUN, running.runId(), null, running.currentStepKey(),
                    "Ein aktiver Prozesslauf wartet auf die Ausführung seines nächsten Schritts");
        }
        if (waitingTime != null) {
            return Route.of(ACTION_WAIT_TIME, waitingTime.runId(), null,
                    waitingTime.currentStepKey(),
                    "Der Prozess wartet bis " + waitingTime.waitUntil()
                            + " — vorher gibt es keinen nächsten Schritt");
        }
        if (waitingEvent != null) {
            return Route.of(ACTION_WAIT_EVENT, waitingEvent.runId(), null,
                    waitingEvent.waitingForEvent(),
                    "Der Prozess wartet auf das Ereignis '" + waitingEvent.waitingForEvent()
                            + "' — erst danach geht es weiter");
        }

        if (facts.hasOverdueDeadline()) {
            return Route.of(ACTION_ESCALATE_OVERDUE_DEADLINE, null, "owner", null,
                    "Eine Frist des Vorgangs ist überfällig — die Eskalation gehört an die Verantwortlichen");
        }
        if (facts.hasUnverifiedDeadline()) {
            return Route.of(ACTION_VERIFY_DEADLINE, null, "owner", null,
                    "Eine Frist des Vorgangs ist noch ungeprüft — sie muss bestätigt oder korrigiert werden");
        }
        if (facts.hasPendingAssignmentTask()) {
            return Route.of(ACTION_ASSIGN_PENDING_TASK, null, "assignment-rules", null,
                    "Es gibt Aufgaben ohne Zuständigkeit — die Zuweisung folgt den Rollenregeln des Mandanten");
        }

        String handover = handoverFor(facts.status());
        if ("NEW".equals(facts.status()) || "IN_PROGRESS".equals(facts.status())
                || "REOPENED".equals(facts.status())) {
            String preset = presetForCaseType(facts.caseTypeCode());
            if (preset != null) {
                return Route.of(ACTION_START_PROCESS, null, handover, preset,
                        "Für den Vorgangstyp ist der Prozess '" + preset
                                + "' vorgesehen — er wird gestartet, wenn keine Lauf existiert");
            }
            return Route.of(ACTION_IDLE, null, handover, null,
                    "Kein Prozessmodell für den Vorgangstyp hinterlegt — der nächste Schritt ist manuell zu wählen");
        }
        if (handover != null) {
            return Route.of(ACTION_IDLE, null, handover, null,
                    "Der Vorgang wartet — die Übergabe liegt bei " + handover);
        }
        return Route.of(ACTION_IDLE, null, null, null, "Kein nächster Schritt ermittelbar");
    }

    /** Whom a waiting case is handed to, derived from its status alone. */
    public static String handoverFor(String caseStatus) {
        if (caseStatus == null) {
            return null;
        }
        return switch (caseStatus.toUpperCase(Locale.ROOT)) {
            case "WAITING_FOR_USER", "WAITING_FOR_APPROVAL", "ON_HOLD" -> "owner";
            case "WAITING_FOR_EXTERNAL_PARTY" -> "external-party";
            case "WAITING_FOR_DOCUMENTS" -> "requester";
            default -> null;
        };
    }

    /**
     * The preset process suggested for a case type, matched by keywords of the type code.
     * Returns {@code null} when no preset fits — the case then needs a manual choice.
     */
    public static String presetForCaseType(String caseTypeCode) {
        if (caseTypeCode == null || caseTypeCode.isBlank()) {
            return null;
        }
        String code = caseTypeCode.toLowerCase(Locale.ROOT);
        if (containsAny(code, "rechnung", "invoice", "einkauf", "kosten")) {
            return "incoming_invoice";
        }
        if (containsAny(code, "vertrag", "contract", "kuendigung", "kündigung")) {
            return "contract_review";
        }
        if (containsAny(code, "behoerde", "behörde", "amt", "bescheid", "authority", "behoerdenpost")) {
            return "authority_letter";
        }
        return null;
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
