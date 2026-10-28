package de.buerokratiecopilot.agents.it;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentInputs;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentWorkspace;
import de.buerokratiecopilot.calendar.CalendarEventService;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code it-patch-window} — Patch-Fenster.
 *
 * <p>Groups the named patches with the affected systems, proposes a maintenance window (or takes
 * the one supplied) and files a calendar entry, a change task with rollback points and a patch plan
 * document. Installation stays approval-gated; the agent installs nothing.</p>
 */
@Component
public class PatchWindowAgent implements Agent {

    private final AgentWorkspace workspace;

    public PatchWindowAgent(AgentWorkspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public String id() {
        return "it-patch-window";
    }

    @Override
    public AgentResult run(AgentContext context) {
        List<String> patches = AgentInputs.list(context.input("patchIds"));
        if (patches.isEmpty()) {
            return AgentResult.skipped("Patch-Fenster: 'patchIds' fehlt — ohne Patches gibt es "
                    + "kein Fenster zu planen.");
        }
        List<String> systems = AgentInputs.list(context.input("affectedSystems"));
        if (systems.isEmpty()) {
            systems = List.of("nicht benannt");
        }
        boolean rollbackRequired = AgentInputs.bool(context.input("rollbackPlanRequired"), true);
        Window window = parseWindow(context.input("maintenanceWindow"), context.now());
        if (window == null) {
            return AgentResult.failed("Patch-Fenster: 'maintenanceWindow' ist nicht lesbar. "
                    + "Erwartet: YYYY-MM-DD, YYYY-MM-DDTHH:MM oder YYYY-MM-DD HH:MM-HH:MM.");
        }
        String sourceKey = "it-patch-window:" + AgentInputs.key(String.join(",", patches),
                String.join(",", systems), window.date().toString(), window.start().toString(),
                window.end().toString());
        String html = "<h1>Patch-Fenster " + window.date() + "</h1>"
                + "<p><strong>Fenster:</strong> " + window.start() + "–" + window.end()
                + " (" + window.note() + ")<br><strong>Patches:</strong> "
                + AgentInputs.html(String.join(", ", patches))
                + "<br><strong>Betroffene Systeme:</strong> "
                + AgentInputs.html(String.join(", ", systems))
                + "<br><strong>Rollback-Plan:</strong> "
                + (rollbackRequired ? "erforderlich — je System Rollback-Punkt festlegen"
                        : "nicht gefordert") + "</p>"
                + "<h2>Vorbereitung</h2><ol><li>Patch-Version und Abhängigkeiten je System prüfen.</li>"
                + "<li>Backup-/Snapshot-Stand vor dem Fenster dokumentieren.</li>"
                + "<li>Rollback-Punkt und Verantwortliche je System festhalten.</li>"
                + "<li>Wartungsfenster kommunizieren und Freigabe einholen.</li></ol>"
                + "<p><em>Installation und Freigabe sind menschliche Schritte; der Agent plant und "
                + "dokumentiert nur.</em></p>";
        AgentWorkspace.DocumentRef document = workspace.ensureDocument(context, sourceKey + ":plan",
                null, null, "Patch-Plan " + window.date(), html,
                Map.of("date", window.date().toString(), "patchCount", patches.size(),
                        "systems", systems.size(), "rollbackRequired", rollbackRequired), List.of());
        if (document.created()) {
            context.addProduced("generated_document", document.id().toString(),
                    "Patch-Plan " + window.date());
        }
        AgentWorkspace.EventRef event = workspace.ensureEvent(context,
                "Patch-Fenster: " + String.join(", ", patches), window.date(),
                CalendarEventService.Kind.APPOINTMENT, null,
                "Wartungsfenster " + window.start() + "–" + window.end() + " (Vorschlag; Freigabe offen)");
        if (event.created()) {
            context.addProduced("calendar_event", event.id().toString(), "Patch-Fenster");
        }
        String title = "Patch-Fenster freigeben: " + window.date();
        AgentWorkspace.TaskRef task = workspace.ensureTask(context, sourceKey + ":approval", title,
                "Patches: " + String.join(", ", patches)
                        + "\nSysteme: " + String.join(", ", systems)
                        + "\nFenster: " + window.date() + " " + window.start() + "–" + window.end()
                        + "\nRollback-Plan erforderlich: " + rollbackRequired
                        + "\n\nInstallation erst nach Freigabe; der Agent installiert nichts.",
                Task.Priority.HIGH,
                window.date().minusDays(1).atTime(12, 0).atZone(AgentInputs.BERLIN).toInstant(),
                List.of("it", "patch"), Map.of("date", window.date().toString(),
                        "patches", patches.size(), "systems", systems.size(),
                        "rollbackRequired", rollbackRequired));
        if (task.created()) {
            context.addProduced("task", task.id().toString(), title);
        }
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("date", window.date().toString());
        meta.put("start", window.start().toString());
        meta.put("end", window.end().toString());
        meta.put("patches", String.valueOf(patches.size()));
        meta.put("systems", String.valueOf(systems.size()));
        meta.put("rollbackRequired", String.valueOf(rollbackRequired));
        meta.put("installed", "false");
        return AgentResult.done("Patch-Fenster für " + patches.size() + " Patch(es) am "
                + window.date() + " " + window.start() + "–" + window.end() + " geplant; "
                + "Installation bleibt freigabepflichtig.", context.produced(), meta);
    }

    /**
     * Reads a maintenance window. Accepted forms: a date, an ISO local date-time, or
     * {@code YYYY-MM-DD HH:MM-HH:MM}. Without input the next Saturday 02:00–06:00 is proposed —
     * marked as a proposal, never as an agreed window. Visible for tests.
     */
    public static Window parseWindow(String raw, java.time.Instant now) {
        if (raw == null || raw.isBlank()) {
            LocalDate saturday = LocalDate.ofInstant(now, AgentInputs.BERLIN)
                    .with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY));
            if (saturday.equals(LocalDate.ofInstant(now, AgentInputs.BERLIN))
                    && ZonedDateTime.ofInstant(now, AgentInputs.BERLIN).toLocalTime()
                            .isAfter(LocalTime.of(2, 0))) {
                saturday = saturday.plusWeeks(1);
            }
            return new Window(saturday, LocalTime.of(2, 0), LocalTime.of(6, 0),
                    "Vorschlag: nächster Samstag, nicht bestätigt");
        }
        String value = raw.strip();
        try {
            if (value.length() == 10) {
                LocalDate date = LocalDate.parse(value);
                return new Window(date, LocalTime.of(2, 0), LocalTime.of(6, 0),
                        "Standardfenster 02:00–06:00, nicht bestätigt");
            }
            if (value.contains("/")) {
                String[] parts = value.split("/", 2);
                LocalDate date = LocalDate.parse(parts[0].substring(0, 10));
                LocalTime start = LocalTime.parse(parts[0].substring(11));
                LocalTime end = LocalTime.parse(parts[1].substring(11));
                if (!end.isAfter(start)) {
                    return null;
                }
                return new Window(date, start, end, "übergebenes Wartungsfenster");
            }
            if (value.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}-\\d{2}:\\d{2}")) {
                LocalDate date = LocalDate.parse(value.substring(0, 10));
                LocalTime start = LocalTime.parse(value.substring(11, 16));
                LocalTime end = LocalTime.parse(value.substring(17));
                if (!end.isAfter(start)) {
                    return null;
                }
                return new Window(date, start, end, "übergebenes Wartungsfenster");
            }
            ZonedDateTime start = ZonedDateTime.parse(value);
            return new Window(start.toLocalDate(), start.toLocalTime(),
                    start.toLocalTime().plusHours(4), "übergebenes Wartungsfenster");
        }
        catch (DateTimeParseException | StringIndexOutOfBoundsException ex) {
            return null;
        }
    }

    /** One maintenance window. */
    public record Window(LocalDate date, LocalTime start, LocalTime end, String note) {
    }
}
