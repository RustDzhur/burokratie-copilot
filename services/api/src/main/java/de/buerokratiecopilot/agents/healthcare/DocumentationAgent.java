package de.buerokratiecopilot.agents.healthcare;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
 * {@code healthcare-documentation} — Dokumentations-Assistenz.
 *
 * <p>Structures the supplied documentation fields, makes missing organisational fields visible and
 * files the result as a draft for human review. It formulates no diagnosis, therapy or care
 * measure; the required fields are administrative (occasion, course, measures, next appointment).
 * </p>
 */
@Component
public class DocumentationAgent implements Agent {

    private static final List<String> REQUIRED_FIELDS = List.of("Anlass", "Verlauf", "Maßnahmen",
            "Nächster Termin");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AgentWorkspace workspace;

    public DocumentationAgent(JdbcTemplate jdbc, ObjectMapper mapper, AgentWorkspace workspace) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.workspace = workspace;
    }

    @Override
    public String id() {
        return "healthcare-documentation";
    }

    @Override
    public AgentResult run(AgentContext context) {
        UUID patientId = AgentInputs.uuid(context.input("patientContactId"));
        LocalDate encounterDate = AgentInputs.date(context.input("encounterDate"));
        String documentType = AgentInputs.clean(context.input("documentType"));
        if (patientId == null || encounterDate == null || documentType == null) {
            return AgentResult.skipped("Dokumentation: 'patientContactId', 'encounterDate' und "
                    + "'documentType' sind erforderlich.");
        }
        String patientName = contactName(context.tenantId(), patientId);
        if (patientName == null) {
            return AgentResult.skipped("Dokumentation: Patient/Kontakt " + patientId
                    + " ist im Mandanten nicht vorhanden.");
        }
        LocalDate today = LocalDate.ofInstant(context.now(), AgentInputs.BERLIN);
        if (encounterDate.isAfter(today)) {
            return AgentResult.failed("Dokumentation: das Begegnungsdatum liegt in der Zukunft.");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        String rawFields = context.input("fields");
        if (rawFields != null && !rawFields.isBlank()) {
            try {
                fields.putAll(mapper.readValue(rawFields,
                        new TypeReference<Map<String, Object>>() {
                        }));
            }
            catch (Exception ex) {
                return AgentResult.failed("Dokumentation: 'fields' ist kein gültiges JSON-Objekt.");
            }
        }
        String text = AgentInputs.clean(context.input("text"));
        if (fields.isEmpty() && text == null) {
            return AgentResult.skipped("Dokumentation: weder strukturierte Felder noch Text "
                    + "übergeben — es gibt nichts zu dokumentieren.");
        }
        List<String> missing = new ArrayList<>();
        for (String required : REQUIRED_FIELDS) {
            if (fields.get(required) == null || String.valueOf(fields.get(required)).isBlank()) {
                missing.add(required);
            }
        }
        String sourceKey = "healthcare-documentation:" + patientId + ":" + encounterDate + ":"
                + AgentInputs.key(documentType, String.valueOf(fields), text);
        String html = html(patientName, encounterDate, documentType, fields, text, missing);
        AgentWorkspace.DocumentRef document = workspace.ensureDocument(context, sourceKey, null,
                patientId, "Dokumentation " + documentType + " " + encounterDate, html, fields,
                missing);
        if (document.created()) {
            context.addProduced("generated_document", document.id().toString(),
                    "Dokumentationsentwurf " + documentType);
        }
        String title = "Dokumentation prüfen: " + documentType + " — " + patientName;
        AgentWorkspace.TaskRef task = workspace.ensureTask(context, sourceKey + ":review", title,
                "Patient/Kontakt: " + patientName
                        + "\nBegegnung: " + encounterDate
                        + "\nDokumenttyp: " + documentType
                        + "\nFehlende Angaben: " + (missing.isEmpty() ? "keine"
                                : String.join(", ", missing))
                        + "\n\nDer Entwurf enthält keine Diagnose, Therapie oder Pflegemaßnahme. "
                        + "Fachliche Prüfung und Freigabe durch die Praxis.",
                Task.Priority.NORMAL, context.now().plus(Duration.ofDays(1)),
                List.of("healthcare", "documentation"),
                Map.of("patientContactId", patientId.toString(),
                        "documentType", documentType, "missing", missing.size()));
        if (task.created()) {
            context.addProduced("task", task.id().toString(), title);
        }
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("patientContactId", patientId.toString());
        meta.put("documentType", documentType);
        meta.put("fields", String.valueOf(fields.size()));
        meta.put("missingFields", String.valueOf(missing.size()));
        meta.put("humanReview", "true");
        return AgentResult.done("Dokumentationsentwurf für " + patientName + " (" + documentType
                + ", " + encounterDate + ") erstellt; " + missing.size()
                + " fehlende Angabe(n) markiert, fachliche Prüfung offen."
                + " Keine Diagnose oder Therapie formuliert.", context.produced(), meta);
    }

    private String contactName(UUID tenantId, UUID contactId) {
        List<String> rows = jdbc.query("""
                select display_name from contacts
                where tenant_id = ? and id = ? and deleted_at is null
                """, (rs, row) -> rs.getString(1), tenantId, contactId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static String html(String patient, LocalDate encounterDate, String documentType,
                               Map<String, Object> fields, String text, List<String> missing) {
        StringBuilder html = new StringBuilder("<h1>Dokumentationsentwurf</h1>")
                .append("<p>Patient/Kontakt: ").append(AgentInputs.html(patient))
                .append("<br>Begegnung: ").append(encounterDate)
                .append("<br>Dokumenttyp: ").append(AgentInputs.html(documentType))
                .append("</p>");
        if (!fields.isEmpty()) {
            html.append("<h2>Strukturierte Angaben</h2><table><tr><th>Feld</th><th>Wert</th></tr>");
            for (Map.Entry<String, Object> entry : fields.entrySet()) {
                html.append("<tr><td>").append(AgentInputs.html(entry.getKey())).append("</td><td>")
                        .append(AgentInputs.html(String.valueOf(entry.getValue()))).append("</td></tr>");
            }
            html.append("</table>");
        }
        if (text != null) {
            html.append("<h2>Freitext</h2><p>")
                    .append(AgentInputs.html(text).replace("\n", "<br>")).append("</p>");
        }
        if (!missing.isEmpty()) {
            html.append("<h2>Fehlende Angaben</h2><ul>");
            for (String field : missing) {
                html.append("<li>").append(AgentInputs.html(field)).append("</li>");
            }
            html.append("</ul>");
        }
        html.append("<p><em>Entwurf zur fachlichen Prüfung. Keine Diagnose, keine Therapie und "
                + "keine Pflegemaßnahme durch den Agenten; die Dokumentation ersetzt keine "
                + "fachliche Dokumentation.</em></p>");
        return html.toString();
    }
}
