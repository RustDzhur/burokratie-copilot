package de.buerokratiecopilot.agents.marketing;

import java.time.Duration;
import java.util.ArrayList;
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
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code marketing-content-brief} — Content-Briefing.
 *
 * <p>Turns a topic, audience and format into a briefing a writer or agency can work from: search
 * intent derived by rules from the topic and keyword, an outline, key terms, open research points
 * and acceptance criteria. It writes no article and invents no facts — sources that must be verified
 * are listed as open points, and the brief is stored in the generated-documents module.</p>
 */
@Component
public class ContentBriefAgent implements Agent {

    private static final List<String> TRANSACTIONAL = List.of("kaufen", "preis", "kosten",
            "bestellen", "anbieter", "vergleich", "günstig", "guenstig", "kostenlos");
    private static final List<String> INFORMATIONAL = List.of("wie ", "was ist", "anleitung",
            "ratgeber", "erklärt", "erklaert", "tipps", "checkliste");

    private final AgentWorkspace workspace;

    public ContentBriefAgent(AgentWorkspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public String id() {
        return "marketing-content-brief";
    }

    @Override
    public AgentResult run(AgentContext context) {
        String topic = AgentInputs.clean(context.input("topic"));
        String audience = AgentInputs.clean(context.input("targetAudience"));
        if (topic == null || audience == null) {
            return AgentResult.skipped("Content-Briefing: 'topic' und 'targetAudience' sind "
                    + "erforderlich.");
        }
        String format = AgentInputs.blankTo(context.input("format"), "BLOG").toUpperCase(Locale.ROOT);
        if (!List.of("BLOG", "LANDINGPAGE", "WHITEPAPER").contains(format)) {
            return AgentResult.failed("Content-Briefing: unbekanntes Format '" + format
                    + "' — erlaubt: BLOG, LANDINGPAGE, WHITEPAPER.");
        }
        int wordCount = AgentInputs.integer(context.input("wordCount"), 1200, 300, 10_000);
        String keyword = AgentInputs.blankTo(context.input("keyword"), topic);
        String tone = AgentInputs.blankTo(context.input("toneOfVoice"), "sachlich, klar");
        Brief brief = build(topic, audience, keyword, format, wordCount, tone);
        String sourceKey = "marketing-content-brief:" + AgentInputs.key(topic, audience, keyword,
                format, String.valueOf(wordCount), tone);

        AgentWorkspace.DocumentRef document = workspace.ensureDocument(context, sourceKey, null, null,
                "Content-Briefing: " + topic, brief.html(),
                Map.of("topic", topic, "audience", audience, "keyword", keyword, "format", format,
                        "wordCount", wordCount),
                List.of());
        if (document.created()) {
            context.addProduced("generated_document", document.id().toString(),
                    "Content-Briefing: " + topic);
        }
        String title = "Content-Briefing prüfen: " + topic;
        AgentWorkspace.TaskRef task = workspace.ensureTask(context, sourceKey + ":approval", title,
                "Briefing für " + format + " zum Thema „" + topic + "“ ("
                        + wordCount + " Wörter, Zielgruppe " + audience + ").\n"
                        + "Suchintention: " + brief.intent() + "\n"
                        + "Offene Punkte: " + String.join("; ", brief.openPoints()),
                Task.Priority.NORMAL, context.now().plus(Duration.ofDays(2)),
                List.of("marketing", "content", format.toLowerCase(Locale.ROOT)),
                Map.of("format", format, "wordCount", wordCount));
        if (task.created()) {
            context.addProduced("task", task.id().toString(), title);
        }
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("format", format);
        meta.put("intent", brief.intent());
        meta.put("wordCount", String.valueOf(wordCount));
        meta.put("outlineSections", String.valueOf(brief.outline().size()));
        return AgentResult.done("Content-Briefing erstellt: „" + topic + "“ (" + format + ", "
                + wordCount + " Wörter, Suchintention " + brief.intent()
                + "); Quellen und Belege sind vor der Veröffentlichung zu prüfen.",
                context.produced(), meta);
    }

    /** The briefing as data, independent of storage. Visible for tests. */
    public static Brief build(String topic, String audience, String keyword, String format,
                              int wordCount, String tone) {
        String text = (topic + " " + keyword).toLowerCase(Locale.GERMAN);
        String intent;
        if (TRANSACTIONAL.stream().anyMatch(text::contains)) {
            intent = "transaktional";
        }
        else if (INFORMATIONAL.stream().anyMatch(text::contains)) {
            intent = "informational";
        }
        else {
            intent = "kommerziell-informierend";
        }
        List<String> outline = new ArrayList<>();
        outline.add("Einleitung: Problem und Nutzen für " + audience);
        outline.add("Kernabschnitt: " + topic + " — Vorgehen und Entscheidungskriterien");
        if ("LANDINGPAGE".equals(format)) {
            outline.add("Leistungsversprechen und Beweis (Fallbeispiel oder Referenz)");
            outline.add("Preis- und Kontaktabschluss mit klarer Handlungsaufforderung");
        }
        else if ("WHITEPAPER".equals(format)) {
            outline.add("Ausgangslage und Datenlage (Quellen verifizieren)");
            outline.add("Lösungsansätze, Grenzen und Umsetzung");
            outline.add("Fazit und weiterführende Quellen");
        }
        else {
            outline.add("Praxisbeispiel oder Schritt-für-Schritt-Anleitung");
            outline.add("Häufige Fragen (aus echten Kundenfragen belegen)");
            outline.add("Fazit mit Handlungsaufforderung");
        }
        List<String> keywords = new ArrayList<>();
        keywords.add(keyword);
        keywords.add(topic + " Anbieter");
        keywords.add(topic + " Kosten");
        keywords.add(topic + " Erfahrungen");
        List<String> openPoints = new ArrayList<>();
        openPoints.add("Quellen und Belege zu jeder Zahlenaussage ergänzen");
        openPoints.add("Praxisbeispiel freigeben (keine erfundenen Referenzen)");
        openPoints.add("Zielgruppen-Ansprache mit echten Kundenfragen abgleichen");

        StringBuilder html = new StringBuilder("<h1>Content-Briefing: ")
                .append(AgentInputs.html(topic)).append("</h1>")
                .append("<p><strong>Zielgruppe:</strong> ").append(AgentInputs.html(audience))
                .append("<br><strong>Format:</strong> ").append(format)
                .append(" (ca. ").append(wordCount).append(" Wörter)")
                .append("<br><strong>Sprache/Ton:</strong> ").append(AgentInputs.html(tone))
                .append("<br><strong>Primärkeyword:</strong> ").append(AgentInputs.html(keyword))
                .append("<br><strong>Suchintention:</strong> ").append(intent).append("</p>")
                .append("<h2>Gliederung</h2><ol>");
        for (String section : outline) {
            html.append("<li>").append(AgentInputs.html(section)).append("</li>");
        }
        html.append("</ol><h2>Kernbegriffe</h2><ul>");
        for (String term : keywords) {
            html.append("<li>").append(AgentInputs.html(term)).append("</li>");
        }
        html.append("</ul><h2>Offene Punkte vor Freigabe</h2><ul>");
        for (String point : openPoints) {
            html.append("<li>").append(AgentInputs.html(point)).append("</li>");
        }
        html.append("</ul><h2>Abnahmekriterien</h2><ul>")
                .append("<li>Primärkeyword kommt in Titel, H1 und mindestens einer Zwischenüberschrift vor.</li>")
                .append("<li>Jede Zahlenaussage hat eine belegte Quelle.</li>")
                .append("<li>Keine erfundenen Referenzen, Preise oder Garantien.</li>")
                .append("<li>Handlungsaufforderung passt zur Suchintention (").append(intent).append(").</li>")
                .append("</ul><p><em>Hinweis: Das Briefing ist eine Arbeitsgrundlage. "
                        + "Veröffentlichung und Recherche bleiben menschliche Schritte.</em></p>");
        return new Brief(intent, List.copyOf(outline), List.copyOf(keywords),
                List.copyOf(openPoints), html.toString());
    }

    /** The resulting briefing. */
    public record Brief(String intent, List<String> outline, List<String> keywords,
                        List<String> openPoints, String html) {
    }
}
