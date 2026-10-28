package de.buerokratiecopilot.calendar;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * One platform event as a standalone iCalendar object, for the CalDAV connection.
 *
 * <p>The feed service renders a whole calendar with one method; a CalDAV PUT needs exactly one VEVENT,
 * so this writer wraps the shared per-event rendering ({@link CalendarFeedService#renderVEvent}) in its own
 * VCALENDAR envelope. The UID and the file name are derived from the same value, so a pushed event can
 * later be found and replaced by UID — the contract the CalDAV client relies on.</p>
 */
public final class IcsEventWriter {

    private IcsEventWriter() {
    }

    /**
     * The stable UID of a platform entry. It matches what the feed publishes ({@code <id>@copilot}),
     * so an external calendar that receives the feed and the push never shows the entry twice.
     */
    public static String eventUid(Map<String, Object> event) {
        return String.valueOf(event.get("id")) + "@copilot";
    }

    /**
     * The file name on the CalDAV server: the UID with {@code :} replaced, because our deadline ids contain
     * colons and the name is used as a URL path segment.
     */
    public static String eventFileName(Map<String, Object> event) {
        return eventUid(event).replace(':', '-') + ".ics";
    }

    /** One event as iCalendar text (VCALENDAR containing one VEVENT). */
    public static String render(Map<String, Object> event, String locale, String baseUrl) {
        StringBuilder out = new StringBuilder();
        CalendarFeedService.line(out, "BEGIN:VCALENDAR");
        CalendarFeedService.line(out, "VERSION:2.0");
        CalendarFeedService.line(out, "PRODID:-//Buerokratie-Copilot//Kalender//DE");
        CalendarFeedService.line(out, "CALSCALE:GREGORIAN");
        CalendarFeedService.line(out, "METHOD:PUBLISH");
        String stamp = OffsetDateTime.now(ZoneOffset.UTC).format(CalendarFeedService.STAMP);
        CalendarFeedService.renderVEvent(out, event, CalendarFeedService.labels(locale), stamp, baseUrl);
        CalendarFeedService.line(out, "END:VCALENDAR");
        return out.toString();
    }
}
