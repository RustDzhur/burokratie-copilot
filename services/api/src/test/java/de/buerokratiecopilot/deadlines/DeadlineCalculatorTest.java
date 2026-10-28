package de.buerokratiecopilot.deadlines;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.deadlines.DeadlineCalculator.CountingMode;
import de.buerokratiecopilot.deadlines.DeadlineCalculator.PeriodUnit;

/**
 * Verifies deadline arithmetic (spec §13.2).
 *
 * <p>The calculator is deliberately separated from the rules: it counts, the rule says what to count.
 * That means the tests here can be exact — a date in, a date out — while the platform as a whole
 * refuses to compute from a rule nobody has verified.</p>
 */
class DeadlineCalculatorTest {

    /** A calendar with one public holiday, so working day counting is testable. */
    private static final Set<LocalDate> HOLIDAYS = Set.of(LocalDate.of(2026, 3, 3));

    @Test
    @DisplayName("calendar days are plain arithmetic and land on a weekend if that is where they land")
    void countsCalendarDays() {
        // 2026-03-02 is a Monday; two calendar days later is Wednesday.
        assertThat(DeadlineCalculator.dueDate(LocalDate.of(2026, 3, 2), 2, PeriodUnit.DAYS,
                CountingMode.CALENDAR_DAYS, HOLIDAYS)).isEqualTo(LocalDate.of(2026, 3, 4));

        // Fourteen calendar days from a Monday is a Monday, two weeks later.
        assertThat(DeadlineCalculator.dueDate(LocalDate.of(2026, 3, 2), 14, PeriodUnit.DAYS,
                CountingMode.CALENDAR_DAYS, HOLIDAYS)).isEqualTo(LocalDate.of(2026, 3, 16));
    }

    @Test
    @DisplayName("working days skip weekends and recorded holidays")
    void countsWorkingDays() {
        // Monday 2026-03-02 plus two working days. With no holiday in the set those are Tuesday and
        // Wednesday; with Tuesday 2026-03-03 recorded as a holiday they are Wednesday and Thursday.
        assertThat(DeadlineCalculator.dueDate(LocalDate.of(2026, 3, 2), 2, PeriodUnit.DAYS,
                CountingMode.WORKING_DAYS, Set.of())).isEqualTo(LocalDate.of(2026, 3, 4));
        assertThat(DeadlineCalculator.dueDate(LocalDate.of(2026, 3, 2), 2, PeriodUnit.DAYS,
                CountingMode.WORKING_DAYS, HOLIDAYS)).isEqualTo(LocalDate.of(2026, 3, 5));
    }

    @Test
    @DisplayName("a holiday inside the period pushes the deadline by exactly one day")
    void holidayPushesTheDeadline() {
        // From Monday 2026-03-02, five working days would be Monday 2026-03-09; with Tuesday
        // 2026-03-03 recorded as a holiday the period ends one day later.
        LocalDate withoutHoliday = DeadlineCalculator.dueDate(LocalDate.of(2026, 3, 2), 5,
                PeriodUnit.DAYS, CountingMode.WORKING_DAYS, Set.of());
        LocalDate withHoliday = DeadlineCalculator.dueDate(LocalDate.of(2026, 3, 2), 5,
                PeriodUnit.DAYS, CountingMode.WORKING_DAYS, HOLIDAYS);

        assertThat(withoutHoliday).isEqualTo(LocalDate.of(2026, 3, 9));
        assertThat(withHoliday).isEqualTo(LocalDate.of(2026, 3, 10));
        assertThat(withoutHoliday.getDayOfWeek()).isEqualTo(java.time.DayOfWeek.MONDAY);
        assertThat(withHoliday.getDayOfWeek()).isEqualTo(java.time.DayOfWeek.TUESDAY);
    }

    @Test
    @DisplayName("the start date is not itself counted as one of the days")
    void startDateIsNotCounted() {
        // One working day from a Monday is Tuesday, not Monday.
        assertThat(DeadlineCalculator.dueDate(LocalDate.of(2026, 3, 2), 1, PeriodUnit.DAYS,
                CountingMode.WORKING_DAYS, Set.of())).isEqualTo(LocalDate.of(2026, 3, 3));
    }

    @Test
    @DisplayName("months and years are calendar units and ignore the counting mode")
    void monthsAndYearsAreCalendarUnits() {
        LocalDate start = LocalDate.of(2026, 1, 31);

        // One month from 31 January is the end of February, not "31 February": this is what a period
        // of one month means, and no counting mode changes it.
        assertThat(DeadlineCalculator.dueDate(start, 1, PeriodUnit.MONTHS, CountingMode.WORKING_DAYS,
                HOLIDAYS)).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(DeadlineCalculator.dueDate(start, 1, PeriodUnit.YEARS, CountingMode.CALENDAR_DAYS,
                HOLIDAYS)).isEqualTo(LocalDate.of(2027, 1, 31));
    }

    @Test
    @DisplayName("weeks in working days mean five days per week")
    void weeksInWorkingDays() {
        // Two weeks as working days is ten working days. From Monday 2026-03-02 that is four days in
        // the first week and six in the second, so the period ends on Monday 2026-03-16 - ten working
        // days are not "the same weekday two weeks later".
        assertThat(DeadlineCalculator.dueDate(LocalDate.of(2026, 3, 2), 2, PeriodUnit.WEEKS,
                CountingMode.WORKING_DAYS, Set.of())).isEqualTo(LocalDate.of(2026, 3, 16));

        // A single week of working days from a Monday does end on the Friday.
        assertThat(DeadlineCalculator.dueDate(LocalDate.of(2026, 3, 2), 1, PeriodUnit.WEEKS,
                CountingMode.WORKING_DAYS, Set.of())).isEqualTo(LocalDate.of(2026, 3, 9));
    }

    @Test
    @DisplayName("Saturday and Sunday are not working days, a recorded holiday is not either")
    void recognisesNonWorkingDays() {
        assertThat(DeadlineCalculator.isWorkingDay(LocalDate.of(2026, 3, 7), Set.of())).isFalse();
        assertThat(DeadlineCalculator.isWorkingDay(LocalDate.of(2026, 3, 8), Set.of())).isFalse();
        assertThat(DeadlineCalculator.isWorkingDay(LocalDate.of(2026, 3, 9), Set.of())).isTrue();
        assertThat(DeadlineCalculator.isWorkingDay(LocalDate.of(2026, 3, 3), HOLIDAYS)).isFalse();
    }

    @Test
    @DisplayName("working days between two dates are counted the same way")
    void countsWorkingDaysBetween() {
        assertThat(DeadlineCalculator.workingDaysBetween(LocalDate.of(2026, 3, 2),
                LocalDate.of(2026, 3, 9), Set.of())).isEqualTo(5);
        assertThat(DeadlineCalculator.workingDaysBetween(LocalDate.of(2026, 3, 2),
                LocalDate.of(2026, 3, 9), HOLIDAYS)).isEqualTo(4);
        assertThat(DeadlineCalculator.workingDaysBetween(LocalDate.of(2026, 3, 9),
                LocalDate.of(2026, 3, 2), Set.of())).isZero();
    }

    @Test
    @DisplayName("an impossible period is refused instead of producing a date")
    void refusesImpossiblePeriods() {
        assertThatThrownBy(() -> DeadlineCalculator.dueDate(LocalDate.of(2026, 3, 2), 0,
                PeriodUnit.DAYS, CountingMode.CALENDAR_DAYS, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> DeadlineCalculator.dueDate(null, 1, PeriodUnit.DAYS,
                CountingMode.CALENDAR_DAYS, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("start date");
    }

    @Test
    @DisplayName("a very long period still terminates, and a broken calendar fails loudly")
    void countingTerminates() {
        assertThat(DeadlineCalculator.dueDate(LocalDate.of(2026, 1, 1), 250, PeriodUnit.DAYS,
                CountingMode.WORKING_DAYS, Set.of()))
                .as("a year of working days ends in the following calendar year")
                .isAfter(LocalDate.of(2026, 12, 1));
    }

    @Test
    @DisplayName("calendar day counting is available for tenants without a holiday calendar")
    void calendarDaysBetweenWorks() {
        assertThat(DeadlineCalculator.calendarDaysBetween(LocalDate.of(2026, 3, 2),
                LocalDate.of(2026, 3, 16))).isEqualTo(14);
    }
}
