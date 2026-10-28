package de.buerokratiecopilot.cases.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import de.buerokratiecopilot.shared.error.ApiException;

/**
 * Which states a case may move between (spec §11.2).
 *
 * <p>The transitions are written out rather than derived from the status names, because the
 * interesting part is not which states exist but which moves are meaningful. A case can go from
 * "in progress" to "waiting for documents" and back; it cannot jump from "new" to "completed" without
 * having been worked on, and it cannot leave "closed" except by being reopened, which is a decision
 * with a reason rather than a slip of the mouse.</p>
 */
public final class CaseStatusMachine {

    private static final Map<Case.Status, Set<Case.Status>> ALLOWED =
            new EnumMap<>(Case.Status.class);

    static {
        // A new case starts being worked on, or is closed at once - a duplicate or a request that
        // needs no action never enters "in progress".
        allow(Case.Status.NEW, Case.Status.IN_PROGRESS, Case.Status.WAITING_FOR_USER,
                Case.Status.WAITING_FOR_DOCUMENTS, Case.Status.ON_HOLD, Case.Status.COMPLETED,
                Case.Status.CLOSED);

        allow(Case.Status.IN_PROGRESS, Case.Status.WAITING_FOR_USER,
                Case.Status.WAITING_FOR_EXTERNAL_PARTY, Case.Status.WAITING_FOR_DOCUMENTS,
                Case.Status.WAITING_FOR_APPROVAL, Case.Status.ON_HOLD, Case.Status.COMPLETED,
                Case.Status.CLOSED);

        // Every waiting state goes back to work, or sideways to another waiting state; waiting for
        // an approval that turns into waiting for documents is a normal day.
        Set<Case.Status> waitingBack = EnumSet.of(Case.Status.IN_PROGRESS, Case.Status.ON_HOLD,
                Case.Status.COMPLETED, Case.Status.CLOSED);
        allow(Case.Status.WAITING_FOR_USER, waitingBack, Case.Status.WAITING_FOR_EXTERNAL_PARTY,
                Case.Status.WAITING_FOR_DOCUMENTS, Case.Status.WAITING_FOR_APPROVAL);
        allow(Case.Status.WAITING_FOR_EXTERNAL_PARTY, waitingBack, Case.Status.WAITING_FOR_USER,
                Case.Status.WAITING_FOR_DOCUMENTS, Case.Status.WAITING_FOR_APPROVAL);
        allow(Case.Status.WAITING_FOR_DOCUMENTS, waitingBack, Case.Status.WAITING_FOR_USER,
                Case.Status.WAITING_FOR_EXTERNAL_PARTY, Case.Status.WAITING_FOR_APPROVAL);
        allow(Case.Status.WAITING_FOR_APPROVAL, waitingBack, Case.Status.WAITING_FOR_USER,
                Case.Status.WAITING_FOR_EXTERNAL_PARTY, Case.Status.WAITING_FOR_DOCUMENTS);

        // A case on hold resumes, waits or is closed.
        allow(Case.Status.ON_HOLD, Case.Status.IN_PROGRESS, Case.Status.WAITING_FOR_USER,
                Case.Status.WAITING_FOR_EXTERNAL_PARTY, Case.Status.WAITING_FOR_DOCUMENTS,
                Case.Status.CLOSED);

        // Completed means the matter is settled but not yet filed away; both are reversible, and
        // reversing them is what the REOPENED state is for.
        allow(Case.Status.COMPLETED, Case.Status.CLOSED, Case.Status.REOPENED);
        allow(Case.Status.CLOSED, Case.Status.REOPENED);
        allow(Case.Status.REOPENED, Case.Status.IN_PROGRESS, Case.Status.WAITING_FOR_USER,
                Case.Status.WAITING_FOR_EXTERNAL_PARTY, Case.Status.WAITING_FOR_DOCUMENTS,
                Case.Status.ON_HOLD, Case.Status.COMPLETED, Case.Status.CLOSED);
    }

    private CaseStatusMachine() {
    }

    private static void allow(Case.Status from, Case.Status... targets) {
        ALLOWED.computeIfAbsent(from, key -> EnumSet.noneOf(Case.Status.class))
                .addAll(java.util.List.of(targets));
    }

    private static void allow(Case.Status from, Set<Case.Status> first, Case.Status... rest) {
        allow(from, rest);
        ALLOWED.get(from).addAll(first);
    }

    public static boolean canTransition(Case.Status from, Case.Status to) {
        if (from == null || to == null) {
            return false;
        }
        return from == to || ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /** The states reachable from here, so an interface can offer only what is possible. */
    public static Set<Case.Status> allowedFrom(Case.Status from) {
        return Set.copyOf(ALLOWED.getOrDefault(from, Set.of()));
    }

    /** Refuses a move the lifecycle does not permit, naming both states. */
    public static void requireTransition(Case.Status from, Case.Status to) {
        if (from == to) {
            throw ApiException.conflict("The case is already " + to);
        }
        if (!canTransition(from, to)) {
            throw ApiException.conflict(
                    "A case cannot go from " + from + " to " + to + "; allowed from here: "
                            + ALLOWED.getOrDefault(from, Set.of()));
        }
    }

    /** Whether reaching this state closes the case, which also decides the closed_at column. */
    public static boolean isClosing(Case.Status status) {
        return status == Case.Status.COMPLETED || status == Case.Status.CLOSED;
    }

    /** Whether the state means the case is waiting for somebody else. */
    public static boolean isWaiting(Case.Status status) {
        return status == Case.Status.WAITING_FOR_USER || status == Case.Status.WAITING_FOR_DOCUMENTS
                || status == Case.Status.WAITING_FOR_EXTERNAL_PARTY
                || status == Case.Status.WAITING_FOR_APPROVAL;
    }
}
