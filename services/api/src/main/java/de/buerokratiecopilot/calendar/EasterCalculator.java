package de.buerokratiecopilot.calendar;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * The date of Easter Sunday, and the offsets of the feasts that move with it.
 *
 * <p>This is arithmetic, not law. The anonymous Gregorian algorithm (Meeus/Jones/Butcher) computes a
 * date that can be checked against any calendar, and the offsets below are simple day differences.
 * Which of those days a region observes as a public holiday is a legal question, and the platform
 * answers none of those: it offers the candidates and a person decides.</p>
 *
 * <p>That separation is why movable feasts are not simply imported as holidays. Auto-importing them
 * would mean the platform asserted which holidays apply where, which it is not entitled to do.</p>
 */
public final class EasterCalculator {

    private EasterCalculator() {
    }

    /** Easter Sunday in the Gregorian calendar for a given year. */
    public static LocalDate easterSunday(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(year, month, day);
    }

    /** A feast that moves with Easter, named by its offset in days. */
    public record MovableFeast(String name, int offsetDays, LocalDate date) {
    }

    /**
     * The days around Easter that are observed as holidays in many German-speaking regions.
     *
     * <p>The list is a list of <em>days</em>, deliberately not a list of holidays: the platform does not
     * know whether a region observes Karfreitag or Pfingstmontag, and a caller therefore receives
     * candidates to confirm rather than holidays to trust.</p>
     */
    public static java.util.List<MovableFeast> movableCandidates(int year) {
        LocalDate easter = easterSunday(year);
        return java.util.List.of(
                new MovableFeast("Karfreitag", -2, easter.plusDays(-2)),
                new MovableFeast("Ostersonntag", 0, easter),
                new MovableFeast("Ostermontag", 1, easter.plusDays(1)),
                new MovableFeast("Himmelfahrt", 39, easter.plusDays(39)),
                new MovableFeast("Pfingstsonntag", 49, easter.plusDays(49)),
                new MovableFeast("Pfingstmontag", 50, easter.plusDays(50)),
                new MovableFeast("Fronleichnam", 60, easter.plusDays(60)));
    }

    /** Days between Easter and the given date, or {@code null} when it is not a movable candidate. */
    public static Integer offsetFromEaster(LocalDate date) {
        if (date == null) {
            return null;
        }
        long offset = ChronoUnit.DAYS.between(easterSunday(date.getYear()), date);
        return java.util.stream.IntStream.of(-2, 0, 1, 39, 49, 50, 60)
                .filter(candidate -> candidate == offset)
                .findFirst()
                .stream().boxed().findFirst().orElse(null);
    }
}
