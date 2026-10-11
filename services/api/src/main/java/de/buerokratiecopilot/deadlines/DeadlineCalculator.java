package de.buerokratiecopilot.deadlines;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/**
 * Computes a deadline from a rule and a start date (spec §13.2).
 *
 * <p>This class is deliberately arithmetic only. It does not know what a reasonable period is, which
 * law applies, or whether a period starts on the day of receipt or the day after — those are properties
 * of the <em>rule</em>, which is configuration a responsible person verifies. The platform's job is to
 * count correctly once somebody has said what to count.</p>
 *
 * <p>That separation is the whole point: a deadline the platform invented would look exactly like one
 * it was told, and only one of them can be defended in front of an authority.</p>
 */
public final class DeadlineCalculator {

    /** What a period is counted in; the values are fixed by the schema. */
    public enum PeriodUnit {
        DAYS, WEEKS, MONTHS, YEARS
    }

    /** Whether weekends and holidays are counted or skipped. */
    public enum CountingMode {
        CALENDAR_DAYS, WORKING_DAYS
    }

    private DeadlineCalculator() {
    }

    /**
     * Adds a period to a start date.
     *
     * @param startDate    the day counting begins, already resolved from the rule's start event
     * @param periodValue  a positive number
     * @param periodUnit   what the number counts
     * @param countingMode whether weekends and holidays are skipped
     * @param holidays     non-working days of the applicable calendar, ignoring weekends
     */
    public static LocalDate dueDate(LocalDate startDate, int periodValue, PeriodUnit periodUnit,
                                    CountingMode countingMode, Set<LocalDate> holidays) {
        if (startDate == null) {
            throw new IllegalArgumentException("A deadline needs a start date");
        }
        if (periodValue <= 0) {
            throw new IllegalArgumentException("A period must be positive, but was " + periodValue);
        }

        PeriodUnit unit = periodUnit == null ? PeriodUnit.DAYS : periodUnit;
        CountingMode mode = countingMode == null ? CountingMode.CALENDAR_DAYS : countingMode;
        Set<LocalDate> nonWorking = holidays == null ? Set.of() : holidays;

        return switch (unit) {
            case DAYS -> mode == CountingMode.WORKING_DAYS
                    ? addWorkingDays(startDate, periodValue, nonWorking)
                    : startDate.plusDays(periodValue);
            case WEEKS -> mode == CountingMode.WORKING_DAYS
                    ? addWorkingDays(startDate, periodValue * 5, nonWorking)
                    : startDate.plusWeeks(periodValue);
            // Months and years are calendar units: a period "of two months" ends on the same day two
            // months later, and no counting mode changes that. Skipping weekends inside a month would
            // silently lengthen it into a different period than the rule states.
            case MONTHS -> startDate.plusMonths(periodValue);
            case YEARS -> startDate.plusYears(periodValue);
        };
    }

    /**
     * Counts working days forward, skipping weekends and the given holidays.
     *
     * <p>The start date itself is never counted — it is the day the period starts from, not a day of
     * the period. Whether counting begins the day after receipt is decided by the rule through its
     * start event, not here.</p>
     */
    public static LocalDate addWorkingDays(LocalDate startDate, int workingDays, Set<LocalDate> holidays) {
        LocalDate date = startDate;
        int remaining = workingDays;
        // Bounded loop: a period of n working days needs at most 2n calendar days plus holidays, and
        // the guard turns a mistake in the data into a clear failure instead of a hang.
        int guard = workingDays * 2 + holidays.size() + 10;
        while (remaining > 0) {
            if (guard-- <= 0) {
                throw new IllegalStateException(
                        "Working day counting did not terminate; check the holiday calendar for "
                                + startDate);
            }
            date = date.plusDays(1);
            if (isWorkingDay(date, holidays)) {
                remaining--;
            }
        }
        return date;
    }

    /** Whether a day counts as a working day: not a weekend and not a recorded holiday. */
    public static boolean isWorkingDay(LocalDate date, Set<LocalDate> holidays) {
        DayOfWeek day = date.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return false;
        }
        return holidays == null || !holidays.contains(date);
    }

    /** Working days between two dates, for the "days remaining" a user sees. */
    public static long workingDaysBetween(LocalDate from, LocalDate to, Set<LocalDate> holidays) {
        if (from == null || to == null || !to.isAfter(from)) {
            return 0;
        }
        long days = 0;
        LocalDate date = from;
        while (date.isBefore(to)) {
            date = date.plusDays(1);
            if (isWorkingDay(date, holidays)) {
                days++;
            }
        }
        return days;
    }

    /** Calendar days between two dates, which is what a tenant without a holiday calendar can use. */
    public static long calendarDaysBetween(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            return 0;
        }
        return ChronoUnit.DAYS.between(from, to);
    }
}
