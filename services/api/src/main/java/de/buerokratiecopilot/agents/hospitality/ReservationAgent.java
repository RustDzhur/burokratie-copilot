package de.buerokratiecopilot.agents.hospitality;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentInputs;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentWorkspace;
import de.buerokratiecopilot.calendar.CalendarEventService;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code hospitality-reservation} — Reservierungen.
 *
 * <p>Checks a reservation against opening hours and the capacity already reserved by open
 * reservation tasks, and either records a confirmed request or proposes alternatives. A table is
 * only promised when capacity allows it; the reservation stays a request until the house confirms
 * it. Allergies and occasions are attached to the record.</p>
 */
@Component
public class ReservationAgent implements Agent {

    private static final int DEFAULT_CAPACITY = 20;

    private final JdbcTemplate jdbc;
    private final AgentWorkspace workspace;

    public ReservationAgent(JdbcTemplate jdbc, AgentWorkspace workspace) {
        this.jdbc = jdbc;
        this.workspace = workspace;
    }

    @Override
    public String id() {
        return "hospitality-reservation";
    }

    @Override
    public AgentResult run(AgentContext context) {
        String guest = AgentInputs.clean(context.input("guestName"));
        LocalDateTime dateTime = AgentInputs.localDateTime(context.input("dateTime"));
        Integer partySize = AgentInputs.integer(context.input("partySize"));
        if (guest == null || dateTime == null || partySize == null) {
            return AgentResult.skipped("Reservierung: 'guestName', 'dateTime' und 'partySize' sind "
                    + "erforderlich.");
        }
        if (partySize <= 0 || partySize > 200) {
            return AgentResult.failed("Reservierung: 'partySize' muss zwischen 1 und 200 liegen.");
        }
        LocalDateTime now = LocalDateTime.ofInstant(context.now(), AgentInputs.BERLIN);
        if (dateTime.isBefore(now)) {
            return AgentResult.skipped("Reservierung: der Termin " + dateTime
                    + " liegt in der Vergangenheit.");
        }
        int capacity = AgentInputs.integer(context.input("capacity"), DEFAULT_CAPACITY, 1, 1000);
        Opening opening = opening(context.input("openingHours"));
        List<String> warnings = new ArrayList<>();
        if (!opening.covers(dateTime.toLocalTime())) {
            warnings.add("Termin liegt außerhalb der Öffnungszeiten (" + opening + ")");
        }
        int reserved = reservedSeats(context.tenantId(), dateTime);
        boolean capacityExceeded = reserved + partySize > capacity;
        String status = capacityExceeded ? "ALTERNATIVE" : "ANGEFRAGT";
        List<LocalDateTime> alternatives = capacityExceeded
                ? List.of(dateTime.plusHours(2), dateTime.plusDays(1)) : List.of();
        String guestEmail = AgentInputs.clean(context.input("guestEmail"));
        String sourceKey = "hospitality-reservation:" + AgentInputs.key(guest,
                dateTime.toString(), String.valueOf(partySize));
        String html = html(guest, dateTime, partySize, capacity, reserved, status, alternatives,
                context.input("occasion"), context.input("notes"), warnings);
        AgentWorkspace.DocumentRef document = workspace.ensureDocument(context, sourceKey, null, null,
                "Reservierung " + guest + " " + dateTime.toLocalDate(), html,
                Map.of("guest", guest, "dateTime", dateTime.toString(),
                        "partySize", partySize, "status", status), List.of());
        if (document.created()) {
            context.addProduced("generated_document", document.id().toString(),
                    "Reservierungsbestätigung " + guest);
        }
        String title = "Reservierung " + (capacityExceeded ? "klären" : "bestätigen") + ": "
                + guest + " " + dateTime;
        AgentWorkspace.TaskRef task = workspace.ensureTask(context, sourceKey + ":confirm", title,
                "Gast: " + guest + (guestEmail == null ? "" : " <" + guestEmail + ">")
                        + "\nTermin: " + dateTime
                        + "\nPersonen: " + partySize
                        + "\nKapazität: " + capacity + ", bereits reserviert: " + reserved
                        + "\nStatus: " + status
                        + (alternatives.isEmpty() ? "" : "\nAlternativen: " + alternatives)
                        + (context.input("occasion") == null ? "" : "\nAnlass: " + context.input("occasion"))
                        + (context.input("notes") == null ? "" : "\nHinweise: " + context.input("notes"))
                        + "\n\nKeine Tischgarantie ohne Bestätigung; der Agent reserviert nichts im "
                        + "Reservierungssystem.",
                capacityExceeded ? Task.Priority.HIGH : Task.Priority.NORMAL,
                dateTime.minusDays(1).atZone(AgentInputs.BERLIN).toInstant(),
                List.of("hospitality", "reservation"),
                Map.of("guest", guest, "dateTime", dateTime.toString(),
                        "partySize", partySize, "status", status,
                        "reservedSeats", reserved));
        if (task.created()) {
            context.addProduced("task", task.id().toString(), title);
        }
        AgentWorkspace.EventRef event = workspace.ensureEvent(context,
                "Reservierung: " + guest, dateTime.toLocalDate(),
                CalendarEventService.Kind.APPOINTMENT, null,
                "Personen: " + partySize + ", Status: " + status + " (" + dateTime.toLocalTime() + ")");
        if (event.created()) {
            context.addProduced("calendar_event", event.id().toString(),
                    "Reservierung " + guest);
        }
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("guest", guest);
        meta.put("dateTime", dateTime.toString());
        meta.put("partySize", String.valueOf(partySize));
        meta.put("capacity", String.valueOf(capacity));
        meta.put("reservedSeats", String.valueOf(reserved));
        meta.put("status", status);
        meta.put("alternatives", String.valueOf(alternatives.size()));
        if (capacityExceeded) {
            return AgentResult.done("Reservierung für " + guest + " am " + dateTime
                    + " passt nicht in die verfügbare Kapazität (" + reserved + "+" + partySize
                    + " von " + capacity + "); Alternativen " + alternatives
                    + " vorgeschlagen. Keine Tischgarantie.", context.produced(), meta);
        }
        return AgentResult.done("Reservierung für " + guest + " am " + dateTime + " ("
                + partySize + " Personen) aufgenommen; Bestätigung durch das Haus offen"
                + (warnings.isEmpty() ? "." : " — " + String.join("; ", warnings) + ".")
                + " Keine Tischgarantie ohne Bestätigung.",
                context.produced(), meta);
    }

    private int reservedSeats(UUID tenantId, LocalDateTime dateTime) {
        LocalDateTime from = dateTime.minusHours(2);
        LocalDateTime to = dateTime.plusHours(2);
        List<Integer> values = jdbc.query("""
                select (metadata->>'reservationPartySize')::int as party
                from tasks
                where tenant_id = ? and deleted_at is null and status not in ('DONE', 'CANCELLED')
                  and metadata->>'reservationAt' is not null
                  and (metadata->>'reservationAt')::timestamptz between ? and ?
                """, (rs, row) -> rs.getInt("party"), tenantId,
                java.sql.Timestamp.valueOf(from), java.sql.Timestamp.valueOf(to));
        return values.stream().mapToInt(Integer::intValue).sum();
    }

    private static Opening opening(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Opening(LocalTime.of(11, 0), LocalTime.of(23, 0), "Standard 11:00–23:00");
        }
        String[] parts = raw.split("-", 2);
        LocalTime start = AgentInputs.time(parts[0]);
        LocalTime end = parts.length > 1 ? AgentInputs.time(parts[1]) : null;
        if (start == null || end == null || !end.isAfter(start)) {
            return new Opening(LocalTime.of(11, 0), LocalTime.of(23, 0), "Standard 11:00–23:00");
        }
        return new Opening(start, end, raw.strip());
    }

    private static String html(String guest, LocalDateTime dateTime, int partySize, int capacity,
                               int reserved, String status, List<LocalDateTime> alternatives,
                               String occasion, String notes, List<String> warnings) {
        StringBuilder html = new StringBuilder("<h1>Reservierung</h1><p><strong>Gast:</strong> ")
                .append(AgentInputs.html(guest)).append("<br><strong>Termin:</strong> ").append(dateTime)
                .append("<br><strong>Personen:</strong> ").append(partySize)
                .append("<br><strong>Kapazität:</strong> ").append(capacity)
                .append(" (belegt: ").append(reserved).append(")<br><strong>Status:</strong> ")
                .append(status).append("</p>");
        if (occasion != null) {
            html.append("<p><strong>Anlass:</strong> ").append(AgentInputs.html(occasion)).append("</p>");
        }
        if (notes != null) {
            html.append("<p><strong>Hinweise/Allergien:</strong> ").append(AgentInputs.html(notes))
                    .append("</p>");
        }
        if (!alternatives.isEmpty()) {
            html.append("<h2>Alternativen</h2><ul>");
            for (LocalDateTime alternative : alternatives) {
                html.append("<li>").append(alternative).append("</li>");
            }
            html.append("</ul>");
        }
        if (!warnings.isEmpty()) {
            html.append("<h2>Hinweise</h2><ul>");
            for (String warning : warnings) {
                html.append("<li>").append(AgentInputs.html(warning)).append("</li>");
            }
            html.append("</ul>");
        }
        html.append("<p><em>Reservierungswunsch, keine Tischgarantie. Bestätigung durch das Haus; "
                + "der Agent schreibt nicht in ein Reservierungssystem.</em></p>");
        return html.toString();
    }

    /** Opening hours with a readable description. */
    public record Opening(LocalTime start, LocalTime end, String description) {

        public boolean covers(LocalTime time) {
            return !time.isBefore(start) && time.isBefore(end);
        }

        @Override
        public String toString() {
            return description;
        }
    }
}
