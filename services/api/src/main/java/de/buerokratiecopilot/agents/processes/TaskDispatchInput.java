package de.buerokratiecopilot.agents.processes;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import de.buerokratiecopilot.tasks.domain.Task;

/**
 * Pure input parsing for {@code task-dispatch}: priorities, due dates, sources and tags are
 * normalised here so the agent's rules are testable without a database.
 */
public final class TaskDispatchInput {

    /** Dates without a time are interpreted in the Berlin zone, like the rest of the platform. */
    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Europe/Berlin");

    private TaskDispatchInput() {
    }

    /** The task priority; absent means the caller's default, unknown text is a hard error. */
    public static Task.Priority parsePriority(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Task.Priority.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("priority '" + raw + "' ist unbekannt");
        }
    }

    /**
     * The due instant from ISO-8601 text; a bare date becomes the end of that day in the Berlin zone.
     * Absent means no due date; invalid text throws for the caller to report.
     */
    public static Instant parseDueAt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.strip();
        try {
            return Instant.parse(value);
        }
        catch (DateTimeParseException instantFailure) {
            try {
                LocalDate date = LocalDate.parse(value);
                return date.atTime(23, 59, 59).atZone(DEFAULT_ZONE).toInstant();
            }
            catch (DateTimeParseException dateFailure) {
                throw new IllegalArgumentException("dueAt '" + raw + "' ist kein ISO-8601-Datum");
            }
        }
    }

    /** The task source; absent means the caller's default, unknown text is a hard error. */
    public static Task.Source parseSource(String raw, Task.Source fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Task.Source.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("source '" + raw + "' ist unbekannt");
        }
    }

    /** Comma-separated tags, trimmed, deduplicated, blanks dropped. */
    public static List<String> parseTags(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        Set<String> tags = new LinkedHashSet<>();
        for (String tag : raw.split(",")) {
            String clean = tag.strip();
            if (!clean.isEmpty()) {
                tags.add(clean);
            }
        }
        return new ArrayList<>(tags);
    }

    /** {@code autoAssign} is on unless the input says exactly "false". */
    public static boolean parseAutoAssign(String raw, boolean fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(
                    "autoAssign '" + raw + "' muss 'true' oder 'false' sein");
        };
    }
}
