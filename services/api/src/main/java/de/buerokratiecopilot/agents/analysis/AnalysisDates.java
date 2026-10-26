package de.buerokratiecopilot.agents.analysis;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * Day arithmetic shared by the analysis agents.
 *
 * <p>All agents count in calendar days of {@value #ZONE_ID} (the platform default time zone):
 * the day a document was created is the Berlin day it was created on, not the UTC day. Inputs
 * that are absent or malformed fall back to documented defaults instead of failing the run.</p>
 */
final class AnalysisDates {

    static final String ZONE_ID = "Europe/Berlin";
    static final ZoneId ZONE = ZoneId.of(ZONE_ID);

    private AnalysisDates() {
    }

    /** The Berlin calendar day of an instant; {@code now} when the instant is absent. */
    static LocalDate day(Instant instant) {
        return (instant == null ? Instant.now() : instant).atZone(ZONE).toLocalDate();
    }

    /** First instant of the day in Berlin (inclusive lower bound). */
    static Instant startOfDay(LocalDate day) {
        return day.atStartOfDay(ZONE).toInstant();
    }

    /** First instant of the next day in Berlin (exclusive upper bound). */
    static Instant endOfDayExclusive(LocalDate day) {
        return day.plusDays(1).atStartOfDay(ZONE).toInstant();
    }

    /** Parses {@code yyyy-MM-dd}; the fallback when the input is absent, blank or malformed. */
    static LocalDate parseDay(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }

    /** A positive integer from an input string; the fallback when absent, blank or invalid. */
    static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** A positive double from an input string; the fallback when absent, blank or invalid. */
    static double parseDouble(String value, double fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            double parsed = Double.parseDouble(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
