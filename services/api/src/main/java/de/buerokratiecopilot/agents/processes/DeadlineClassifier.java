package de.buerokratiecopilot.agents.processes;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Pure classification of a deadline against "today": overdue, due soon (within the lead window) or
 * fine. Part of {@code deadline-watch}; kept as a pure function so the escalation rules are testable
 * exactly — a deadline yesterday is overdue, a deadline tomorrow with a 7-day lead is due soon, and
 * a deadline in a month is quiet.
 */
public final class DeadlineClassifier {

    private DeadlineClassifier() {
    }

    public enum Bucket {
        OVERDUE, DUE_SOON, OK
    }

    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Europe/Berlin");

    /** The calendar day a deadline falls on, from its date or its instant. */
    public static LocalDate dueDay(LocalDate dueDate, Instant dueAt) {
        if (dueDate != null) {
            return dueDate;
        }
        if (dueAt != null) {
            return dueAt.atZone(DEFAULT_ZONE).toLocalDate();
        }
        return null;
    }

    /**
     * @param leadDays how many days ahead still count as "soon"
     */
    public static Bucket classify(LocalDate dueDate, Instant dueAt, Instant now, int leadDays) {
        LocalDate due = dueDay(dueDate, dueAt);
        if (due == null) {
            // A deadline without a date cannot be watched; it is not overdue.
            return Bucket.OK;
        }
        LocalDate today = (now == null ? Instant.now() : now).atZone(DEFAULT_ZONE).toLocalDate();
        int days = (int) Math.max(0, leadDays);
        if (due.isBefore(today)) {
            return Bucket.OVERDUE;
        }
        if (!due.isAfter(today.plusDays(days))) {
            return Bucket.DUE_SOON;
        }
        return Bucket.OK;
    }
}
