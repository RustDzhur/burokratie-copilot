package de.buerokratiecopilot.agents.healthcare;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import de.buerokratiecopilot.notifications.domain.Notification;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code healthcare-appointment} — Terminvergabe.
 *
 * <p>Takes an appointment request, classifies its urgency, checks the patient contact and preferred
 * period and files a scheduling task (plus a tentative calendar entry when a concrete date is
 * given). An emergency is flagged immediately. The agent never confirms a slot — it does not know
 * the practice's actual availability — and gives no medical advice.</p>
 */
@Component
public class AppointmentAgent implements Agent {

    private final JdbcTemplate jdbc;
    private final AgentWorkspace workspace;

    public AppointmentAgent(JdbcTemplate jdbc, AgentWorkspace workspace) {
        this.jdbc = jdbc;
        this.workspace = workspace;
    }

    @Override
    public String id() {
        return "healthcare-appointment";
    }

    @Override
    public AgentResult run(AgentContext context) {
        String type = AgentInputs.clean(context.input("appointmentType"));
        if (type == null) {
            return AgentResult.skipped("Terminvergabe: 'appointmentType' fehlt.");
        }
        String urgency = AgentInputs.blankTo(context.input("urgency"), "NORMAL").toUpperCase(Locale.ROOT);
        if (!List.of("NORMAL", "URGENT", "EMERGENCY").contains(urgency)) {
            return AgentResult.failed("Terminvergabe: unbekannte Dringlichkeit '" + urgency + "'.");
        }
        UUID patientId = AgentInputs.uuid(context.input("patientContactId"));
        if (context.input("patientContactId") != null && !context.input("patientContactId").isBlank()
                && patientId == null) {
            return AgentResult.failed("Terminvergabe: 'patientContactId' ist keine gültige UUID.");
        }
        String patientName = null;
        if (patientId != null) {
            List<String> rows = jdbc.query("""
                    select display_name from contacts
                    where tenant_id = ? and id = ? and deleted_at is null
                    """, (rs, row) -> rs.getString(1), context.tenantId(), patientId);
            if (rows.isEmpty()) {
                return AgentResult.skipped("Terminvergabe: Patient/Kontakt " + patientId
                        + " ist im Mandanten nicht vorhanden.");
            }
            patientName = rows.getFirst();
        }
        UUID practitionerId = AgentInputs.uuid(context.input("practitionerId"));
        String period = AgentInputs.clean(context.input("preferredPeriod"));
        LocalDateTime dateTime = AgentInputs.localDateTime(period);
        if (dateTime == null && period != null && period.length() == 10) {
            LocalDate day = AgentInputs.date(period);
            if (day != null) {
                dateTime = day.atTime(9, 0);
            }
        }
        String sourceKey = "healthcare-appointment:" + (patientId == null ? "anonym" : patientId)
                + ":" + type + ":" + (period == null ? "" : period);
        int dueMinutes = switch (urgency) {
            case "EMERGENCY" -> 60;
            case "URGENT" -> 240;
            default -> 1440;
        };
        String title = ("EMERGENCY".equals(urgency) ? "SOFORT Termin: " : "Terminwunsch: ")
                + type + (patientName == null ? "" : " — " + patientName);
        AgentWorkspace.TaskRef task = workspace.ensureTask(context, sourceKey, title,
                "Terminart: " + type
                        + "\nPatient/Kontakt: " + (patientName == null ? "nicht benannt" : patientName)
                        + "\nDringlichkeit: " + urgency
                        + "\nPraktiker: " + (practitionerId == null ? "nicht benannt" : practitionerId)
                        + "\nWunschzeitraum: " + (period == null ? "offen" : period)
                        + "\n\nVerbindliche Vergabe durch die Praxis; der Agent kennt keine freien "
                        + "Slots. Keine medizinische Beratung.",
                switch (urgency) {
                    case "EMERGENCY" -> Task.Priority.CRITICAL;
                    case "URGENT" -> Task.Priority.HIGH;
                    default -> Task.Priority.NORMAL;
                },
                context.now().plus(Duration.ofMinutes(dueMinutes)),
                List.of("healthcare", "appointment", urgency.toLowerCase(Locale.ROOT)),
                Map.of("appointmentType", type, "urgency", urgency,
                        "patientContactId", patientId == null ? "" : patientId.toString()));
        if (!task.created()) {
            context.addProduced("task", task.id().toString(), "Bestehender Terminwunsch");
            return AgentResult.done("Terminwunsch für " + type
                    + " ist bereits als Aufgabe vorhanden (" + task.id() + ").", context.produced(),
                    Map.of("existing", "true", "taskId", task.id().toString()));
        }
        context.addProduced("task", task.id().toString(), title);
        boolean event = false;
        if (dateTime != null) {
            AgentWorkspace.EventRef created = workspace.ensureEvent(context,
                    "Termin (vorläufig): " + type + (patientName == null ? "" : " — " + patientName),
                    dateTime.toLocalDate(), CalendarEventService.Kind.APPOINTMENT, null,
                    "Vorläufiger Terminwunsch, Bestätigung offen (" + dateTime.toLocalTime() + ")");
            event = created.created();
            if (event) {
                context.addProduced("calendar_event", created.id().toString(), "Vorläufiger Termin");
            }
        }
        String html = "<h1>Terminanfrage</h1><p>Terminart: " + AgentInputs.html(type)
                + "<br>Patient/Kontakt: " + AgentInputs.html(patientName == null ? "nicht benannt"
                        : patientName)
                + "<br>Dringlichkeit: " + urgency
                + "<br>Wunsch: " + AgentInputs.html(period == null ? "offen" : period)
                + "<br>Praktiker: " + (practitionerId == null ? "nicht benannt" : practitionerId)
                + "</p><p><em>Keine verbindliche Terminvergabe und keine medizinische Beratung. "
                + "Die Praxis prüft Verfügbarkeit und bestätigt.</em></p>";
        AgentWorkspace.DocumentRef document = workspace.ensureDocument(context, sourceKey + ":doc",
                null, patientId, "Terminanfrage " + type, html,
                Map.of("appointmentType", type, "urgency", urgency), List.of());
        if (document.created()) {
            context.addProduced("generated_document", document.id().toString(), "Terminanfrage");
        }
        boolean notified = false;
        if ("EMERGENCY".equals(urgency)) {
            notified = workspace.notify(context, context.actorUserId(),
                    "HEALTHCARE_EMERGENCY_APPOINTMENT", Notification.Category.TASK,
                    Notification.Severity.CRITICAL, title,
                    "Notfallmarkierung: sofortige organisatorische Prüfung durch die Praxis",
                    "task", task.id(), sourceKey);
            if (notified) {
                context.addProduced("notification", task.id().toString(), "Notfallhinweis Termin");
            }
        }
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("appointmentType", type);
        meta.put("urgency", urgency);
        meta.put("patientContactId", patientId == null ? "" : patientId.toString());
        meta.put("calendarEventCreated", String.valueOf(event));
        meta.put("notified", String.valueOf(notified));
        meta.put("confirmed", "false");
        return AgentResult.done("Terminanfrage " + type + " erfasst (Dringlichkeit " + urgency
                + "); Bestätigung durch die Praxis offen" + (event ? ", vorläufiger Kalendereintrag"
                        : "") + ".", context.produced(), meta);
    }
}
