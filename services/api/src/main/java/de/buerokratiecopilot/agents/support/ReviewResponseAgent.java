package de.buerokratiecopilot.agents.support;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
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
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code support-review-response} — Bewertungsantworten.
 *
 * <p>Classifies a public review by rating and wording into praise, factual criticism or justified
 * anger, pulls verifiable facts from the referenced invoice, and drafts a response in the requested
 * tone. It makes no admission of liability and promises nothing. The draft is stored and an
 * approval task is filed; publication stays a human step.</p>
 */
@Component
public class ReviewResponseAgent implements Agent {

    private final JdbcTemplate jdbc;
    private final AgentWorkspace workspace;

    public ReviewResponseAgent(JdbcTemplate jdbc, AgentWorkspace workspace) {
        this.jdbc = jdbc;
        this.workspace = workspace;
    }

    @Override
    public String id() {
        return "support-review-response";
    }

    @Override
    public AgentResult run(AgentContext context) {
        String reviewText = AgentInputs.clean(context.input("reviewText"));
        String platform = AgentInputs.clean(context.input("platform"));
        Integer rating = AgentInputs.integer(context.input("rating"));
        if (reviewText == null || platform == null || rating == null) {
            return AgentResult.skipped("Bewertungsantwort: 'reviewText', 'rating' und 'platform' "
                    + "sind erforderlich.");
        }
        if (rating < 1 || rating > 5) {
            return AgentResult.failed("Bewertungsantwort: 'rating' muss zwischen 1 und 5 liegen.");
        }
        String tone = AgentInputs.blankTo(context.input("tone"), "SACHLICH").toUpperCase(Locale.ROOT);
        if (!List.of("SACHLICH", "FREUNDLICH", "BESTIMMT").contains(tone)) {
            return AgentResult.failed("Bewertungsantwort: unbekannter Ton '" + tone
                    + "' — erlaubt: SACHLICH, FREUNDLICH, BESTIMMT.");
        }
        Classification classification = classify(reviewText, rating);
        Map<String, Object> facts = findFacts(context.tenantId(), context.input("orderReference"));
        String response = draft(classification, tone, facts, platform);
        String sourceKey = "support-review-response:" + AgentInputs.key(platform,
                String.valueOf(rating), reviewText);
        String html = "<h1>Bewertungsantwort (" + AgentInputs.html(platform) + ")</h1>"
                + "<p><strong>Bewertung:</strong> " + rating + "/5<br><strong>Einordnung:</strong> "
                + classification.kind() + " — " + classification.rationale()
                + "<br><strong>Tonalität:</strong> " + tone + "</p>"
                + "<h2>Bewertungstext</h2><blockquote>" + AgentInputs.html(reviewText)
                + "</blockquote><h2>Antwortentwurf</h2><p>"
                + AgentInputs.html(response).replace("\n", "<br>") + "</p>"
                + "<p><em>Entwurf. Keine Schuldanerkennung, keine Zusage ohne Prüfung; "
                + "Veröffentlichung nur nach Freigabe.</em></p>";
        AgentWorkspace.DocumentRef document = workspace.ensureDocument(context, sourceKey, null, null,
                "Bewertungsantwort " + platform, html,
                Map.of("platform", platform, "rating", rating, "classification",
                        classification.kind()), List.of());
        if (document.created()) {
            context.addProduced("generated_document", document.id().toString(),
                    "Bewertungsantwort " + platform);
        }
        String title = "Bewertungsantwort freigeben: " + platform + " (" + rating + "/5)";
        AgentWorkspace.TaskRef task = workspace.ensureTask(context, sourceKey + ":approval", title,
                "Antwortentwurf für " + platform + ":\n" + response
                        + "\n\nNichts veröffentlicht; Freigabe durch eine Person.",
                classification.kind().equals("BERECHTIGTER_AERGER")
                        ? Task.Priority.HIGH : Task.Priority.NORMAL,
                context.now().plus(Duration.ofDays(2)),
                List.of("support", "review"), Map.of("platform", platform,
                        "rating", rating, "classification", classification.kind()));
        if (task.created()) {
            context.addProduced("task", task.id().toString(), title);
        }
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("classification", classification.kind());
        meta.put("rating", String.valueOf(rating));
        meta.put("platform", platform);
        meta.put("published", "false");
        return AgentResult.done("Bewertung auf " + platform + " als " + classification.kind()
                + " eingeordnet; Antwortentwurf erstellt und zur Freigabe vorgelegt — nichts "
                + "veröffentlicht.", context.produced(), meta);
    }

    /** Classifies a review by rating and wording; pure and testable. */
    public static Classification classify(String reviewText, int rating) {
        String text = reviewText == null ? "" : reviewText.toLowerCase(Locale.GERMAN);
        boolean factual = List.of("falsche lieferung", "fehlende", "beschädigt", "beschaedigt",
                "zu spät", "zu spaet", "rechnung", "retoure", "erstattung", "termin")
                .stream().anyMatch(text::contains);
        boolean angry = List.of("unverschämt", "unverschaemt", "frech", "katastrophe", "betrug",
                "nie wieder", "anwalt", "schlimm", "entsetzt", "wütend", "wuetend")
                .stream().anyMatch(text::contains);
        if (rating <= 2 && (angry || !factual)) {
            return new Classification("BERECHTIGTER_AERGER",
                    "Niedrige Bewertung mit emotionalem oder nicht sachlich belegtem Kern");
        }
        if (rating >= 4 && !angry) {
            return new Classification("LOB", "Hohe Bewertung ohne Beschwerdesignal");
        }
        return new Classification("SACHLICHE_KRITIK",
                factual ? "Konkreter Sachverhalt genannt" : "Mittlere Bewertung mit Kritik");
    }

    /** The response text; facts are cited only when the platform really has them. */
    public static String draft(Classification classification, String tone,
                               Map<String, Object> facts, String platform) {
        String opening;
        String body;
        String closing;
        switch (classification.kind()) {
            case "LOB" -> {
                opening = tone.equals("FREUNDLICH")
                        ? "Vielen Dank für Ihre freundliche Bewertung!"
                        : "Vielen Dank für Ihre Rückmeldung.";
                body = "Es freut uns, dass Sie mit unserem Service zufrieden waren.";
                closing = "Wir freuen uns auf Ihren nächsten Besuch.";
            }
            case "SACHLICHE_KRITIK" -> {
                opening = "Vielen Dank, dass Sie sich die Zeit für Ihre Rückmeldung genommen haben.";
                body = "Wir nehmen Ihre Kritik ernst und prüfen den geschilderten Vorgang.";
                closing = "Wir melden uns nach der Prüfung mit einer sachlichen Rückmeldung.";
            }
            default -> {
                opening = tone.equals("BESTIMMT")
                        ? "Wir haben Ihre Bewertung zur Kenntnis genommen und prüfen den Vorgang."
                        : "Vielen Dank für Ihre offene Rückmeldung.";
                body = "Es tut uns leid, dass Sie mit der Abwicklung nicht zufrieden waren. "
                        + "Wir prüfen den Sachverhalt anhand der uns vorliegenden Unterlagen.";
                closing = "Bitte geben Sie uns die Gelegenheit, den Vorgang sachlich zu klären.";
            }
        }
        StringBuilder response = new StringBuilder(opening).append("\n\n");
        if (facts.get("invoiceNumber") != null) {
            response.append("Zu Ihrer Bestellung/Rechnung ").append(facts.get("invoiceNumber"));
            if (facts.get("issueDate") != null) {
                response.append(" vom ").append(facts.get("issueDate"));
            }
            if (facts.get("amount") != null) {
                response.append(" über ").append(facts.get("amount")).append(" ")
                        .append(facts.getOrDefault("currency", "EUR"));
            }
            response.append(": ").append(body).append("\n\n");
        }
        else {
            response.append(body).append("\n\n");
        }
        response.append(closing).append("\n\nMit freundlichen Grüßen\nIhr Team");
        return response.toString();
    }

    private Map<String, Object> findFacts(UUID tenantId, String reference) {
        if (reference == null || reference.isBlank()) {
            return Map.of();
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select invoice_number, issue_date, gross_amount, currency
                from invoices
                where tenant_id = ? and deleted_at is null and invoice_number = ?
                order by issue_date desc limit 1
                """, tenantId, reference.strip());
        if (rows.isEmpty()) {
            return Map.of("reference", reference.strip(), "found", "false");
        }
        Map<String, Object> row = rows.getFirst();
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("invoiceNumber", row.get("invoice_number"));
        facts.put("issueDate", row.get("issue_date"));
        BigDecimal amount = row.get("gross_amount") == null ? null
                : (BigDecimal) row.get("gross_amount");
        facts.put("amount", amount == null ? null : amount.toPlainString());
        facts.put("currency", row.get("currency") == null ? "EUR" : row.get("currency"));
        facts.put("found", "true");
        return facts;
    }

    /** How the review reads. */
    public record Classification(String kind, String rationale) {
    }
}
