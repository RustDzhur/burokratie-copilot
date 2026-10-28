package de.buerokratiecopilot.agents.it;

import java.time.Duration;
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
import de.buerokratiecopilot.notifications.domain.Notification;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code it-incident} — Störungsmeldung.
 *
 * <p>Classifies a reported outage by the affected systems and the wording, files an incident task
 * with an SLA and a protocol document, and notifies the reporter. An existing open incident with
 * the same title is reported instead of duplicated. The agent does not repair anything and does not
 * close an incident.</p>
 */
@Component
public class IncidentAgent implements Agent {

    private static final List<String> CRITICAL = List.of("ausfall", "total", "produktiv", "datenverlust",
            "sicherheit", "ransomware", "verschlüsselt", "verschluesselt", "keine verbindung");
    private static final List<String> HIGH = List.of("viele", "blockiert", "funktioniert nicht",
            "fehler", "langsam", "unterbrochen", "teilausfall");

    private final AgentWorkspace workspace;

    public IncidentAgent(AgentWorkspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public String id() {
        return "it-incident";
    }

    @Override
    public AgentResult run(AgentContext context) {
        String title = AgentInputs.clean(context.input("title"));
        if (title == null) {
            return AgentResult.skipped("Störungsmeldung: 'title' fehlt.");
        }
        String description = AgentInputs.clean(context.input("description"));
        List<String> systems = AgentInputs.list(context.input("affectedSystems"));
        String reportedBy = AgentInputs.blankTo(context.input("reportedBy"), "unbekannt");
        Severity severity = classify(title, description, systems, context.input("severityHint"));
        String sourceKey = "it-incident:" + AgentInputs.key(title, description == null ? "" : description,
                String.join(",", systems));
        String taskTitle = "Störung: " + AgentWorkspace.truncate(title);
        AgentWorkspace.TaskRef task = workspace.ensureTask(context, sourceKey, taskTitle,
                "Gemeldet von: " + reportedBy
                        + "\nBetroffene Systeme: " + (systems.isEmpty() ? "nicht benannt"
                                : String.join(", ", systems))
                        + "\nSchweregrad: " + severity.name() + " (" + severity.rationale() + ")"
                        + "\n\nBeschreibung:\n" + (description == null ? "keine" : description)
                        + "\n\nDer Agent behebt nichts; IT-Team übernehmen und Status dokumentieren.",
                severity.priority(), context.now().plus(Duration.ofMinutes(severity.slaMinutes())),
                List.of("it", "incident", severity.name().toLowerCase(Locale.ROOT)),
                Map.of("severity", severity.name(), "systems", String.join(",", systems),
                        "reportedBy", reportedBy));
        if (!task.created()) {
            context.addProduced("task", task.id().toString(), "Bestehender Incident");
            return AgentResult.done("Bekannte Störung: es ist bereits ein offener Incident mit "
                    + "diesem Titel vorhanden (" + task.id() + "); kein zweiter angelegt.",
                    context.produced(), Map.of("existing", "true", "taskId", task.id().toString()));
        }
        context.addProduced("task", task.id().toString(), taskTitle);
        String html = "<h1>Incident-Protokoll</h1><p><strong>Störung:</strong> "
                + AgentInputs.html(title) + "<br><strong>Schweregrad:</strong> " + severity.name()
                + " (" + AgentInputs.html(severity.rationale()) + ")<br><strong>Gemeldet von:</strong> "
                + AgentInputs.html(reportedBy) + "<br><strong>Betroffene Systeme:</strong> "
                + AgentInputs.html(systems.isEmpty() ? "nicht benannt" : String.join(", ", systems))
                + "<br><strong>Erste Reaktion bis:</strong> "
                + context.now().plus(Duration.ofMinutes(severity.slaMinutes())) + "</p>"
                + "<h2>Beschreibung</h2><p>"
                + AgentInputs.html(description == null ? "keine" : description)
                + "</p><h2>Nächste Schritte</h2><ol><li>Auswirkung bestätigen und Umfang eingrenzen.</li>"
                + "<li>Verantwortliche im IT-Team benennen.</li>"
                + "<li>Behebung und Kommunikation dokumentieren.</li></ol>"
                + "<p><em>Der Agent dokumentiert und benachrichtigt; Behebung und Abschluss sind "
                + "menschliche Schritte.</em></p>";
        AgentWorkspace.DocumentRef document = workspace.ensureDocument(context, sourceKey + ":protocol",
                null, null, "Incident: " + title, html,
                Map.of("severity", severity.name(), "systems", String.join(",", systems)), List.of());
        if (document.created()) {
            context.addProduced("generated_document", document.id().toString(),
                    "Incident-Protokoll: " + title);
        }
        boolean notified = workspace.notify(context, context.actorUserId(), "IT_INCIDENT",
                Notification.Category.TASK,
                "CRITICAL".equals(severity.name()) ? Notification.Severity.CRITICAL
                        : Notification.Severity.WARNING,
                taskTitle, severity.rationale(), "task", task.id(), sourceKey);
        if (notified) {
            context.addProduced("notification", task.id().toString(), "Incident-Benachrichtigung");
        }
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("severity", severity.name());
        meta.put("slaMinutes", String.valueOf(severity.slaMinutes()));
        meta.put("systems", String.join(",", systems));
        meta.put("notified", String.valueOf(notified));
        meta.put("resolved", "false");
        return AgentResult.done("Störung erfasst: " + title + " — Schweregrad " + severity.name()
                + ", Erstreaktion innerhalb von " + severity.slaMinutes() + " Minuten.", context.produced(), meta);
    }

    /** Deterministic severity; the reporter's hint wins, otherwise keywords and system count. */
    public static Severity classify(String title, String description, List<String> systems,
                                    String hint) {
        Severity hinted = parse(hint);
        if (hinted != null) {
            return new Severity(hinted.name(), hinted.priority(), hinted.slaMinutes(),
                    "Vom Melder angegebener Schweregrad");
        }
        String text = (title == null ? "" : title).toLowerCase(Locale.GERMAN) + " "
                + (description == null ? "" : description).toLowerCase(Locale.GERMAN);
        if (CRITICAL.stream().anyMatch(text::contains) || systems.size() >= 3) {
            return new Severity("CRITICAL", Task.Priority.CRITICAL, 60,
                    "Kritisches Schlüsselwort oder mehrere betroffene Systeme");
        }
        if (HIGH.stream().anyMatch(text::contains) || systems.size() == 2) {
            return new Severity("HIGH", Task.Priority.HIGH, 240,
                    "Dringliches Schlüsselwort oder zwei betroffene Systeme");
        }
        return new Severity("MEDIUM", Task.Priority.NORMAL, 1440,
                "Kein kritisches Signal erkannt; Standardbearbeitung");
    }

    private static Severity parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return switch (raw.strip().toUpperCase(Locale.ROOT)) {
                case "CRITICAL" -> new Severity("CRITICAL", Task.Priority.CRITICAL, 60, "");
                case "HIGH" -> new Severity("HIGH", Task.Priority.HIGH, 240, "");
                case "MEDIUM" -> new Severity("MEDIUM", Task.Priority.NORMAL, 1440, "");
                case "LOW" -> new Severity("LOW", Task.Priority.LOW, 4320, "");
                default -> null;
            };
        }
        catch (RuntimeException ex) {
            return null;
        }
    }

    /** How severe an incident is. */
    public record Severity(String name, Task.Priority priority, int slaMinutes, String rationale) {
    }
}
