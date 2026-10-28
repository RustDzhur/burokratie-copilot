package de.buerokratiecopilot.agents.legal;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentInputs;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentWorkspace;
import de.buerokratiecopilot.cases.domain.CaseEvent;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code legal-nda-check} — NDA-Prüfung.
 *
 * <p>Reads the extracted text of the NDA document and checks the clause groups the catalogue names:
 * confidentiality, term, contractual penalty, exceptions and jurisdiction. Missing clause groups
 * and one-sided formulations become findings with a text location. This is a clerical pre-check,
 * not legal advice, and the report says so; publication or signature stays a human/legal decision.</p>
 */
@Component
public class NdaCheckAgent implements Agent {

    private static final Pattern YEARS = Pattern.compile("(\\d{1,2})\\s*(jahr|jahre|jahren|monat|monate|monaten)");
    private static final List<String> ONE_SIDED = List.of("ausschließlich zugunsten", "ausschliesslich zugunsten",
            "weltweit", "unbefristet", "ohne angemessene gegenleistung", "pauschal");

    private final JdbcTemplate jdbc;
    private final AgentWorkspace workspace;

    public NdaCheckAgent(JdbcTemplate jdbc, AgentWorkspace workspace) {
        this.jdbc = jdbc;
        this.workspace = workspace;
    }

    @Override
    public String id() {
        return "legal-nda-check";
    }

    @Override
    public AgentResult run(AgentContext context) {
        UUID documentId = AgentInputs.uuid(context.input("documentId"));
        if (documentId == null) {
            return AgentResult.skipped("NDA-Prüfung: 'documentId' fehlt oder ist keine gültige UUID.");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, original_filename, document_number,
                       coalesce(extracted_text, '') as extracted_text,
                       coalesce(summary, '') as summary
                from documents
                where tenant_id = ? and id = ? and deleted_at is null
                """, context.tenantId(), documentId);
        if (rows.isEmpty()) {
            return AgentResult.skipped("NDA-Prüfung: Dokument " + documentId
                    + " ist im Mandanten nicht vorhanden.");
        }
        Map<String, Object> document = rows.getFirst();
        String text = (String.valueOf(document.get("extracted_text")) + "\n"
                + String.valueOf(document.get("summary"))).strip();
        if (text.isBlank()) {
            return AgentResult.skipped("NDA-Prüfung: für Dokument " + documentId
                    + " liegt kein extrahierter Text vor — ohne Text keine Klauselprüfung.");
        }
        Analysis analysis = analyse(text);
        String filename = String.valueOf(document.get("original_filename"));
        UUID caseId = findCase(context.tenantId(), documentId);
        String sourceKey = "legal-nda-check:" + documentId + ":" + AgentInputs.key(text);
        String html = html(filename, analysis);
        AgentWorkspace.DocumentRef report = workspace.ensureDocument(context, sourceKey, caseId, null,
                "NDA-Prüfbericht: " + filename, html,
                Map.of("documentId", documentId.toString(), "findings", analysis.findings().size(),
                        "risk", analysis.risk()), List.of());
        if (report.created()) {
            context.addProduced("generated_document", report.id().toString(),
                    "NDA-Prüfbericht: " + filename);
        }
        Task.Priority priority = "HOCH".equals(analysis.risk()) ? Task.Priority.HIGH
                : Task.Priority.NORMAL;
        String title = "NDA prüfen/freigeben: " + filename;
        AgentWorkspace.TaskRef task = workspace.ensureTask(context, sourceKey + ":review", title,
                "Dokument: " + filename
                        + "\nRisikostufe: " + analysis.risk()
                        + "\nBefunde: " + (analysis.findings().isEmpty() ? "keine"
                                : String.join("; ", analysis.findings()))
                        + "\n\nHinweis: maschinelle Vorprüfung, keine Rechtsberatung. "
                        + "Anwaltliche Prüfung und Freigabe bleiben menschliche Schritte.",
                priority, context.now().plus(Duration.ofDays(2)),
                List.of("legal", "nda"),
                Map.of("documentId", documentId.toString(), "risk", analysis.risk()));
        if (task.created()) {
            context.addProduced("task", task.id().toString(), title);
        }
        if (caseId != null) {
            workspace.caseEvent(context, caseId, CaseEvent.EventType.NOTE_ADDED,
                    "NDA-Prüfung durchgeführt", "Bericht und Prüfaufgabe angelegt; keine "
                            + "Rechtsberatung.", report.id(), task.id(), null,
                    Map.of("agent", "legal-nda-check", "documentId", documentId.toString()));
        }
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("documentId", documentId.toString());
        meta.put("findings", String.valueOf(analysis.findings().size()));
        meta.put("risk", analysis.risk());
        meta.put("hasConfidentiality", String.valueOf(analysis.hasConfidentiality()));
        meta.put("hasTerm", String.valueOf(analysis.hasTerm()));
        meta.put("hasPenalty", String.valueOf(analysis.hasPenalty()));
        meta.put("hasExceptions", String.valueOf(analysis.hasExceptions()));
        meta.put("hasJurisdiction", String.valueOf(analysis.hasJurisdiction()));
        meta.put("termYears", analysis.termYears() == null ? "" : String.valueOf(analysis.termYears()));
        return AgentResult.done("NDA „" + filename + "“ geprüft: " + analysis.findings().size()
                + " Befund(e), Risikostufe " + analysis.risk()
                + "; maschinelle Vorprüfung, keine Rechtsberatung.", context.produced(), meta);
    }

    /** Clause checks as a pure function; visible for tests. */
    public static Analysis analyse(String rawText) {
        String text = rawText == null ? "" : rawText.toLowerCase(Locale.GERMAN);
        boolean confidentiality = containsAny(text, "vertraulich", "geheimhaltung", "vertrauliche informationen");
        boolean term = containsAny(text, "laufzeit", "vertragsdauer", "dauer der vereinbarung",
                "gilt für", "endet am");
        boolean penalty = containsAny(text, "vertragsstrafe", "pönale", "poenale", "strafzahlung");
        boolean exceptions = containsAny(text, "ausnahme", "gesetzlich vorgeschrieben", "offenlegung",
                "empfänger", "empfaenger");
        boolean jurisdiction = containsAny(text, "gerichtsstand", "gericht", "schiedsgericht",
                "schiedsklausel", "anwendbares recht");
        Integer termYears = null;
        Matcher matcher = YEARS.matcher(text);
        if (matcher.find()) {
            int value = Integer.parseInt(matcher.group(1));
            if (matcher.group(2).startsWith("monat")) {
                termYears = Math.max(1, Math.round(value / 12.0f));
            }
            else {
                termYears = value;
            }
        }
        List<String> findings = new ArrayList<>();
        if (!confidentiality) {
            findings.add("Geheimhaltungspflicht nicht ausdrücklich geregelt");
        }
        if (!term) {
            findings.add("Laufzeit/Dauer nicht ausdrücklich geregelt");
        }
        if (!penalty) {
            findings.add("Vertragsstrafe nicht geregelt (kann gewollt sein)");
        }
        if (!exceptions) {
            findings.add("Ausnahmen von der Geheimhaltung nicht geregelt");
        }
        if (!jurisdiction) {
            findings.add("Gerichtsstand/anwendbares Recht nicht geregelt");
        }
        for (String phrase : ONE_SIDED) {
            if (text.contains(phrase)) {
                findings.add("Einseitige oder pauschale Formulierung: „" + phrase + "“");
            }
        }
        if (termYears != null && termYears > 5) {
            findings.add("Laufzeit von " + termYears + " Jahren ist ungewöhnlich lang");
        }
        String risk = findings.size() >= 3 || !confidentiality || !jurisdiction ? "HOCH"
                : findings.isEmpty() ? "NIEDRIG" : "MITTEL";
        return new Analysis(confidentiality, term, penalty, exceptions, jurisdiction, termYears,
                List.copyOf(findings), risk);
    }

    private static boolean containsAny(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private UUID findCase(UUID tenantId, UUID documentId) {
        List<UUID> rows = jdbc.query("""
                select case_id from case_documents
                where tenant_id = ? and document_id = ? limit 1
                """, (rs, row) -> rs.getObject("case_id", UUID.class), tenantId, documentId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static String html(String filename, Analysis analysis) {
        StringBuilder html = new StringBuilder("<h1>NDA-Prüfbericht: ")
                .append(AgentInputs.html(filename)).append("</h1><p><strong>Risikostufe:</strong> ")
                .append(analysis.risk()).append("</p><h2>Geprüfte Klauselgruppen</h2><ul>")
                .append("<li>Geheimhaltung: ").append(analysis.hasConfidentiality() ? "gefunden" : "fehlt").append("</li>")
                .append("<li>Laufzeit: ").append(analysis.hasTerm() ? "gefunden" : "fehlt")
                .append(analysis.termYears() == null ? "" : " (" + analysis.termYears() + " Jahre)").append("</li>")
                .append("<li>Vertragsstrafe: ").append(analysis.hasPenalty() ? "gefunden" : "fehlt").append("</li>")
                .append("<li>Ausnahmen: ").append(analysis.hasExceptions() ? "gefunden" : "fehlt").append("</li>")
                .append("<li>Gerichtsstand/Recht: ").append(analysis.hasJurisdiction() ? "gefunden" : "fehlt").append("</li>")
                .append("</ul><h2>Befunde</h2>");
        if (analysis.findings().isEmpty()) {
            html.append("<p>Keine Auffälligkeit in den geprüften Klauselgruppen.</p>");
        }
        else {
            html.append("<ul>");
            for (String finding : analysis.findings()) {
                html.append("<li>").append(AgentInputs.html(finding)).append("</li>");
            }
            html.append("</ul>");
        }
        html.append("<p><em>Maschinelle Vorprüfung ohne Rechtsberatung. Die Prüfung ersetzt keine "
                + "anwaltliche Bewertung; Freigabe und Unterschrift bleiben menschliche "
                + "Entscheidungen.</em></p>");
        return html.toString();
    }

    /** The clause result. */
    public record Analysis(boolean hasConfidentiality, boolean hasTerm, boolean hasPenalty,
                           boolean hasExceptions, boolean hasJurisdiction, Integer termYears,
                           List<String> findings, String risk) {
    }
}
