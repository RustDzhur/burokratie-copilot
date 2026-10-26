package de.buerokratiecopilot.agents.processes;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.calendar.CalendarEventService;

/**
 * {@code calendar-sync} — Kalender-Sync.
 *
 * <p>This edition ships the internal calendar only: the CalDAV adapter that used to compare the
 * internal calendar against an external server was removed from the platform. The agent therefore
 * reports the internal calendar state (appointments, reminders, upcoming events) and says plainly
 * that an external comparison is unavailable — an honest answer instead of a silent nothing.</p>
 */
@Component
public class CalendarSyncAgent implements Agent {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    private final CalendarEventService events;

    public CalendarSyncAgent(CalendarEventService events) {
        this.events = events;
    }

    @Override
    public String id() {
        return "calendar-sync";
    }

    @Override
    public AgentResult run(AgentContext context) {
        int daysAhead = parseInt(context.input("daysAhead"), 30);
        UUID tenantId = context.tenantId();

        List<Map<String, Object>> internal;
        try {
            internal = events.events(tenantId, context.actorUserId(), true,
                    LocalDate.now(ZONE), LocalDate.now(ZONE).plusDays(Math.min(daysAhead, 400)));
        } catch (Exception e) {
            return AgentResult.failed("Interner Kalender nicht lesbar: " + e.getMessage());
        }

        long appointments = internal.stream()
                .filter(e -> "APPOINTMENT".equals(String.valueOf(e.get("kind")))).count();
        long reminders = internal.stream()
                .filter(e -> "REMINDER".equals(String.valueOf(e.get("kind")))).count();

        Map<String, String> meta = new LinkedHashMap<>(Map.of(
                "daysAhead", String.valueOf(daysAhead),
                "internalEvents", String.valueOf(internal.size()),
                "appointments", String.valueOf(appointments),
                "reminders", String.valueOf(reminders),
                "externalComparison", "unavailable"));

        return AgentResult.done(
                "Interner Kalender gelesen: " + internal.size() + " Ereignisse ("
                        + appointments + " Termine, " + reminders
                        + " Erinnerungen). Externer Kalenderabgleich ist in dieser Edition nicht verfügbar.",
                context.produced(), meta);
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
