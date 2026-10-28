package de.buerokratiecopilot.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The iCalendar text a CalDAV push carries: the same UID the feed publishes, the shared rendering
 * (alarms, escaping, confidential masking) and the file-name contract the client relies on.
 */
class IcsEventWriterTest {

    private static Map<String, Object> event(String id, String type, String title) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", id);
        event.put("title", title);
        event.put("date", "2026-11-05");
        event.put("end_date", null);
        event.put("type", type);
        event.put("case_reference", null);
        event.put("note", null);
        event.put("document_id", null);
        event.put("confidential", false);
        return event;
    }

    @Test
    @DisplayName("the UID matches the feed's UID and the file name carries the same identity")
    void uidAndFileName() {
        assertThat(IcsEventWriter.eventUid(event("11111111-2222-3333-4444-555555555555",
                "APPOINTMENT", "Titel"))).isEqualTo("11111111-2222-3333-4444-555555555555@copilot");
        assertThat(IcsEventWriter.eventFileName(event("11111111-2222-3333-4444-555555555555",
                "APPOINTMENT", "Titel"))).isEqualTo("11111111-2222-3333-4444-555555555555@copilot.ics");

        // Deadline ids contain colons; the file name must stay a single path segment.
        assertThat(IcsEventWriter.eventUid(event("task-deadline:aaaa-bbbb", "DEADLINE", "Frist")))
                .isEqualTo("task-deadline:aaaa-bbbb@copilot");
        assertThat(IcsEventWriter.eventFileName(event("task-deadline:aaaa-bbbb", "DEADLINE", "Frist")))
                .isEqualTo("task-deadline-aaaa-bbbb@copilot.ics")
                .doesNotContain(":");
    }

    @Test
    @DisplayName("renders a complete calendar object with one VEVENT, the UID, date values and CRLF")
    void rendersEvent() {
        String ics = IcsEventWriter.render(event("11111111-2222-3333-4444-555555555555",
                "APPOINTMENT", "Beratung"), "de", "https://example.app");

        assertThat(ics).startsWith("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n");
        assertThat(ics).endsWith("END:VCALENDAR\r\n");
        assertThat(ics).contains("UID:11111111-2222-3333-4444-555555555555@copilot\r\n");
        assertThat(ics).contains("DTSTART;VALUE=DATE:20261105\r\n");
        // An all-day event ends the day after its end date, as the feed does.
        assertThat(ics).contains("DTEND;VALUE=DATE:20261106\r\n");
        assertThat(ics).contains("SUMMARY:Beratung\r\n");
        // Every content line is folded at 75 octets and terminated by CRLF.
        for (String line : ics.split("\r\n")) {
            assertThat(line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(75);
        }
    }

    @Test
    @DisplayName("escapes line breaks and separators in the title, as the feed does")
    void escapes() {
        String ics = IcsEventWriter.render(event("aaaa-bbbb",
                "APPOINTMENT", "Zeile 1\nZeile 2; mit Komma, und \\ Backslash"), "de", "https://example.app");

        assertThat(ics).contains("SUMMARY:Zeile 1\\nZeile 2\\; mit Komma\\, und \\\\ Backslash\r\n");
    }

    @Test
    @DisplayName("a deadline carries three alarms, an appointment two — the same as the feed")
    void alarms() {
        String deadline = IcsEventWriter.render(event("a-1", "DEADLINE", "Frist"), "de",
                "https://example.app");
        assertThat(deadline).contains("BEGIN:VALARM").contains("-P2DT15H").contains("-PT15H").contains("PT8H");

        String appointment = IcsEventWriter.render(event("a-2", "APPOINTMENT", "Termin"), "de",
                "https://example.app");
        assertThat(appointment).contains("BEGIN:VALARM").doesNotContain("-P2DT15H");
    }

    @Test
    @DisplayName("a confidential entry is pushed masked, with the platform's label, never the title")
    void confidential() {
        Map<String, Object> secret = event("a-3", "APPOINTMENT", "Krebsbefund");
        secret.put("confidential", true);
        String ics = IcsEventWriter.render(secret, "de", "https://example.app");

        assertThat(ics).contains("SUMMARY:Vertraulicher Eintrag");
        assertThat(ics).doesNotContain("Krebsbefund");
    }

    @Test
    @DisplayName("the URL back into the platform is in the description")
    void linkBack() {
        String ics = IcsEventWriter.render(event("a-4", "APPOINTMENT", "Termin"), "de",
                "https://example.app");
        assertThat(ics).contains("DESCRIPTION:")
                .contains("https://example.app/calendar");
    }
}
