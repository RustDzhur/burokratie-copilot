package de.buerokratiecopilot.agents.healthcare;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentInputs;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentWorkspace;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code healthcare-recall} — Recall-Erinnerungen.
 *
 * <p>Builds the recall list from the due dates and consent flags the practice stores on the contact
 * ({@code metadata.recall}). Only organisational data is used: no diagnosis, no medical
 * recommendation. Consent is honoured, missing contact data is flagged, and nothing is sent — the
 * list is approval-gated.</p>
 */
@Component
public class RecallAgent implements Agent {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AgentWorkspace workspace;

    public RecallAgent(JdbcTemplate jdbc, ObjectMapper mapper, AgentWorkspace workspace) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.workspace = workspace;
    }

    @Override
    public String id() {
        return "healthcare-recall";
    }

    @Override
    public AgentResult run(AgentContext context) {
        String recallType = AgentInputs.clean(context.input("recallType"));
        if (recallType == null) {
            return AgentResult.skipped("Recall: 'recallType' fehlt.");
        }
        int dueWithinDays = AgentInputs.integer(context.input("dueWithinDays"), 30, 1, 730);
        boolean consentRequired = AgentInputs.bool(context.input("consentRequired"), true);
        LocalDate today = LocalDate.ofInstant(context.now(), AgentInputs.BERLIN);
        LocalDate horizon = today.plusDays(dueWithinDays);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, display_name, email::text as email, phone, mobile, metadata::text as metadata
                from contacts
                where tenant_id = ? and deleted_at is null and metadata->'recall' is not null
                """, context.tenantId());
        List<Recall> recalls = new ArrayList<>();
        int withoutConsent = 0;
        int wrongType = 0;
        for (Map<String, Object> row : rows) {
            Recall recall = parse(row, recallType);
            if (recall == null) {
                wrongType++;
                continue;
            }
            if (recall.dueDate() == null || recall.dueDate().isAfter(horizon)) {
                continue;
            }
            if (consentRequired && !recall.consent()) {
                withoutConsent++;
                continue;
            }
            recalls.add(recall);
        }
        if (recalls.isEmpty()) {
            return AgentResult.skipped("Recall: keine fällige Erinnerung vom Typ '" + recallType
                    + "' innerhalb von " + dueWithinDays + " Tagen"
                    + (withoutConsent > 0 ? " (" + withoutConsent + " ohne Einwilligung übersprungen)"
                            : "") + ".");
        }
        recalls.sort(java.util.Comparator.comparing(Recall::dueDate));
        String sourceKey = "healthcare-recall:" + recallType + ":" + today + ":"
                + AgentInputs.key(recalls.stream().map(r -> r.contactId().toString()).toList().toString());
        String html = html(recallType, today, horizon, recalls, withoutConsent);
        AgentWorkspace.DocumentRef document = workspace.ensureDocument(context, sourceKey, null, null,
                "Recall-Liste " + recallType + " " + today, html,
                Map.of("recallType", recallType, "dueWithinDays", dueWithinDays,
                        "patients", recalls.size(), "withoutConsent", withoutConsent), List.of());
        if (document.created()) {
            context.addProduced("generated_document", document.id().toString(),
                    "Recall-Liste " + recallType);
        }
        int tasks = 0;
        for (Recall recall : recalls.stream().limit(100).toList()) {
            String title = "Recall: " + recallType + " — " + recall.name();
            String channel = recall.email() != null ? "E-Mail"
                    : recall.phone() != null || recall.mobile() != null ? "Telefon" : "offen";
            AgentWorkspace.TaskRef task = workspace.ensureTask(context,
                    sourceKey + ":patient:" + recall.contactId(), title,
                    "Patient/Kontakt: " + recall.name()
                            + "\nRecall-Typ: " + recallType
                            + "\nFällig: " + recall.dueDate()
                            + "\nEinwilligung: " + (recall.consent() ? "liegt vor" : "nicht erforderlich")
                            + "\nKanal: " + channel
                            + (recall.note() == null ? "" : "\nHinweis: " + recall.note())
                            + "\n\nTermin/Erinnerung durch die Praxis; der Agent versendet nichts "
                            + "und trifft keine medizinische Entscheidung.",
                    Task.Priority.NORMAL,
                    recall.dueDate().isBefore(today) ? context.now().plus(Duration.ofDays(1))
                            : recall.dueDate().atTime(9, 0).atZone(AgentInputs.BERLIN).toInstant(),
                    List.of("healthcare", "recall"), Map.of("contactId", recall.contactId().toString(),
                            "recallType", recallType));
            if (task.created()) {
                tasks++;
                context.addProduced("task", task.id().toString(), title);
            }
            context.addProduced("recall-patient", recall.contactId().toString(),
                    recall.name() + " — fällig " + recall.dueDate());
        }
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("recallType", recallType);
        meta.put("patients", String.valueOf(recalls.size()));
        meta.put("tasksCreated", String.valueOf(tasks));
        meta.put("withoutConsentSkipped", String.valueOf(withoutConsent));
        meta.put("otherTypesIgnored", String.valueOf(wrongType));
        meta.put("sent", "false");
        return AgentResult.done("Recall-Liste '" + recallType + "': " + recalls.size()
                + " Patient/Kontakte fällig bis " + horizon + ", " + tasks
                + " Aufgabe(n) angelegt" + (withoutConsent > 0 ? ", " + withoutConsent
                        + " ohne Einwilligung übersprungen" : "") + "; nichts versendet.",
                context.produced(), meta);
    }

    private Recall parse(Map<String, Object> row, String recallType) {
        try {
            Map<String, Object> metadata = mapper.readValue(String.valueOf(row.get("metadata")),
                    new TypeReference<Map<String, Object>>() {
                    });
            if (!(metadata.get("recall") instanceof Map<?, ?> recall)) {
                return null;
            }
            String type = recall.get("type") == null ? null : String.valueOf(recall.get("type"));
            if (type == null || !type.equalsIgnoreCase(recallType)) {
                return null;
            }
            LocalDate due = AgentInputs.date(recall.get("dueDate"));
            boolean consent = Boolean.parseBoolean(String.valueOf(recall.get("consent")));
            return new Recall((UUID) row.get("id"), String.valueOf(row.get("display_name")),
                    text(row.get("email")), text(row.get("phone")), text(row.get("mobile")),
                    due, consent, recall.get("note") == null ? null : String.valueOf(recall.get("note")));
        }
        catch (Exception ex) {
            return null;
        }
    }

    private static String html(String recallType, LocalDate today, LocalDate horizon,
                               List<Recall> recalls, int withoutConsent) {
        StringBuilder html = new StringBuilder("<h1>Recall-Liste ").append(AgentInputs.html(recallType))
                .append("</h1><p>Stichtag: ").append(today).append(", Horizont: ").append(horizon)
                .append(", Kontakte: ").append(recalls.size())
                .append(", ohne Einwilligung übersprungen: ").append(withoutConsent).append("</p>")
                .append("<table><tr><th>Kontakt</th><th>Fällig</th><th>Kanal</th>"
                        + "<th>Einwilligung</th><th>Hinweis</th></tr>");
        for (Recall recall : recalls) {
            String channel = recall.email() != null ? "E-Mail"
                    : recall.phone() != null || recall.mobile() != null ? "Telefon" : "offen";
            html.append("<tr><td>").append(AgentInputs.html(recall.name())).append("</td><td>")
                    .append(recall.dueDate()).append("</td><td>").append(channel).append("</td><td>")
                    .append(recall.consent() ? "liegt vor" : "nicht erforderlich").append("</td><td>")
                    .append(AgentInputs.html(recall.note() == null ? "" : recall.note()))
                    .append("</td></tr>");
        }
        html.append("</table><p><em>Organisatorische Erinnerungsliste; keine Diagnose und keine "
                + "medizinische Empfehlung. Versand erst nach Freigabe der Praxis.</em></p>");
        return html.toString();
    }

    private static String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).strip();
    }

    /** One recall candidate. */
    public record Recall(UUID contactId, String name, String email, String phone, String mobile,
                         LocalDate dueDate, boolean consent, String note) {
    }
}
