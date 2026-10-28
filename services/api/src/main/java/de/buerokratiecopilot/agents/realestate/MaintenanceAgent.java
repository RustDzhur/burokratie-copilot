package de.buerokratiecopilot.agents.realestate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentInputs;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentWorkspace;
import de.buerokratiecopilot.notifications.domain.Notification;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code realestate-maintenance} — Objektwartung.
 *
 * <p>Records a damage/maintenance report, classifies the trade by keywords and the urgency by the
 * supplied level, and prepares a contractor brief with access instructions and an approval gate.
 * No contractor is commissioned; the preferred contractor is recorded, not assigned.</p>
 */
@Component
public class MaintenanceAgent implements Agent {

    private final AgentWorkspace workspace;

    public MaintenanceAgent(AgentWorkspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public String id() {
        return "realestate-maintenance";
    }

    @Override
    public AgentResult run(AgentContext context) {
        UUID propertyId = AgentInputs.uuid(context.input("propertyId"));
        String issue = AgentInputs.clean(context.input("issueDescription"));
        if (propertyId == null || issue == null) {
            return AgentResult.skipped("Objektwartung: 'propertyId' und 'issueDescription' sind "
                    + "erforderlich.");
        }
        String urgency = AgentInputs.blankTo(context.input("urgency"), "NORMAL").toUpperCase(Locale.ROOT);
        if (!List.of("LOW", "NORMAL", "HIGH", "EMERGENCY").contains(urgency)) {
            return AgentResult.failed("Objektwartung: unbekannte Dringlichkeit '" + urgency + "'.");
        }
        Trade trade = classify(issue);
        int dueHours = switch (urgency) {
            case "EMERGENCY" -> 4;
            case "HIGH" -> 24;
            case "LOW" -> 168;
            default -> 72;
        };
        String sourceKey = "realestate-maintenance:" + propertyId + ":" + AgentInputs.key(issue);
        String html = "<h1>Wartungsauftrag vorbereiten</h1><p><strong>Objekt:</strong> " + propertyId
                + "<br><strong>Gewerk:</strong> " + trade.label
                + "<br><strong>Dringlichkeit:</strong> " + urgency
                + "<br><strong>Zugang:</strong> "
                + AgentInputs.html(AgentInputs.blankTo(context.input("accessInstructions"), "nicht angegeben"))
                + "<br><strong>Wunsch-Dienstleister:</strong> "
                + AgentInputs.html(AgentInputs.blankTo(context.input("preferredContractor"), "nicht benannt"))
                + "</p><h2>Mangel</h2><p>" + AgentInputs.html(issue)
                + "</p><h2>Auftragsumfang</h2><ul><li>Schaden vor Ort bestätigen und Umfang "
                + "dokumentieren.</li><li>Kostenvoranschlag und Termin beim Dienstleister anfragen.</li>"
                + "<li>Freigabe einholen, erst dann beauftragen.</li>"
                + "<li>Ausführung und Abnahme mit Fotos dokumentieren.</li></ul>"
                + "<p><em>Der Agent beauftragt keinen Dienstleister und gibt keine Kosten frei.</em></p>";
        AgentWorkspace.DocumentRef document = workspace.ensureDocument(context, sourceKey, null, null,
                "Wartung " + propertyId + " — " + trade.label, html,
                Map.of("propertyId", propertyId.toString(), "trade", trade.label,
                        "urgency", urgency), List.of());
        if (document.created()) {
            context.addProduced("generated_document", document.id().toString(),
                    "Wartungsbriefing " + propertyId);
        }
        String title = "Wartung beauftragen: " + propertyId + " — " + trade.label;
        AgentWorkspace.TaskRef task = workspace.ensureTask(context, sourceKey + ":approve", title,
                "Objekt: " + propertyId
                        + "\nGewerk: " + trade.label + " (" + trade.rationale + ")"
                        + "\nDringlichkeit: " + urgency
                        + "\nMangel: " + issue
                        + (context.input("accessInstructions") == null ? ""
                                : "\nZugang: " + context.input("accessInstructions"))
                        + (context.input("preferredContractor") == null ? ""
                                : "\nWunsch-Dienstleister: " + context.input("preferredContractor"))
                        + "\n\nAuftrag erst nach Freigabe; der Agent beauftragt nichts.",
                switch (urgency) {
                    case "EMERGENCY" -> Task.Priority.CRITICAL;
                    case "HIGH" -> Task.Priority.HIGH;
                    case "LOW" -> Task.Priority.LOW;
                    default -> Task.Priority.NORMAL;
                },
                context.now().plus(Duration.ofHours(dueHours)),
                List.of("realestate", "maintenance", trade.key),
                Map.of("propertyId", propertyId.toString(), "trade", trade.label,
                        "urgency", urgency));
        if (task.created()) {
            context.addProduced("task", task.id().toString(), title);
        }
        boolean notified = false;
        if ("EMERGENCY".equals(urgency)) {
            notified = workspace.notify(context, context.actorUserId(), "MAINTENANCE_EMERGENCY",
                    Notification.Category.TASK, Notification.Severity.CRITICAL, title, issue,
                    "task", task.id(), sourceKey);
            if (notified) {
                context.addProduced("notification", task.id().toString(),
                        "Notfall-Wartungshinweis");
            }
        }
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("propertyId", propertyId.toString());
        meta.put("trade", trade.label);
        meta.put("urgency", urgency);
        meta.put("dueHours", String.valueOf(dueHours));
        meta.put("notified", String.valueOf(notified));
        meta.put("commissioned", "false");
        return AgentResult.done("Wartung für Objekt " + propertyId + " erfasst: Gewerk "
                + trade.label + ", Dringlichkeit " + urgency + ", Reaktion in " + dueHours
                + " h; Dienstleister nicht beauftragt.", context.produced(), meta);
    }

    /** Deterministic trade classification by keywords; pure and testable. */
    public static Trade classify(String issue) {
        String text = issue == null ? "" : issue.toLowerCase(Locale.GERMAN);
        if (contains(text, "schimmel", "feucht", "wasser", "rohr", "toilette", "sanitär", "sanitaer")) {
            return new Trade("sanitaer", "Sanitär/Wasser", "Wasser- oder Feuchtigkeitssignal");
        }
        if (contains(text, "strom", "steckdose", "licht", "sicherung", "elektro")) {
            return new Trade("elektro", "Elektro", "Strom- oder Elektrosignal");
        }
        if (contains(text, "heizung", "thermostat", "wärme", "waerme", "kessel")) {
            return new Trade("heizung", "Heizung", "Heizungssignal");
        }
        if (contains(text, "dach", "regen", "undicht", "fassade")) {
            return new Trade("dach", "Dach/Fassade", "Gebäudehülle betroffen");
        }
        if (contains(text, "tür", "tuer", "fenster", "schloss", "schlüssel", "schluessel")) {
            return new Trade("schlosserei", "Türen/Fenster", "Öffnung oder Verschluss betroffen");
        }
        return new Trade("allgemein", "Allgemein/Handwerk", "Kein spezifisches Gewerk erkannt");
    }

    private static boolean contains(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /** The classified trade. */
    public record Trade(String key, String label, String rationale) {
    }
}
