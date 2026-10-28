package de.buerokratiecopilot.agents.marketing;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
 * {@code marketing-seo-audit} — SEO-Audit.
 *
 * <p>The pilot does not crawl the web. It audits the page facts supplied as JSON (title, meta
 * description, H1 count, internal links, word count) against fixed, explainable signals and
 * prioritises the findings by impact and effort. A missing page list is an honest skip, not an
 * invented audit.</p>
 */
@Component
public class SeoAuditAgent implements Agent {

    static final int MAX_PAGES = 200;

    private final ObjectMapper mapper;
    private final AgentWorkspace workspace;

    public SeoAuditAgent(ObjectMapper mapper, AgentWorkspace workspace) {
        this.mapper = mapper;
        this.workspace = workspace;
    }

    @Override
    public String id() {
        return "marketing-seo-audit";
    }

    @Override
    public AgentResult run(AgentContext context) {
        String siteUrl = AgentInputs.clean(context.input("siteUrl"));
        if (siteUrl == null) {
            return AgentResult.skipped("SEO-Audit: 'siteUrl' fehlt.");
        }
        String rawPages = context.input("pages");
        if (rawPages == null || rawPages.isBlank()) {
            return AgentResult.skipped("SEO-Audit: keine Seitendaten übergeben. Der Pilot ruft "
                    + "keine Website ab; 'pages' als JSON-Liste mit url/title/metaDescription/"
                    + "h1Count/internalLinks/wordCount ist erforderlich.");
        }
        List<Map<String, Object>> pages;
        try {
            pages = mapper.readValue(rawPages, new TypeReference<List<Map<String, Object>>>() {
            });
        }
        catch (Exception ex) {
            return AgentResult.failed("SEO-Audit: 'pages' ist kein gültiges JSON-Array.");
        }
        if (pages.isEmpty()) {
            return AgentResult.skipped("SEO-Audit: die Seitenliste ist leer.");
        }
        List<String> keywords = AgentInputs.list(context.input("targetKeywords"));
        List<PageAudit> audits = audit(pages.stream().limit(MAX_PAGES).toList(), keywords);
        List<PageAudit> withFindings = audits.stream().filter(a -> !a.findings().isEmpty()).toList();
        if (withFindings.isEmpty()) {
            return AgentResult.done("SEO-Audit für " + siteUrl + ": " + audits.size()
                    + " Seite(n) geprüft, keine Auffälligkeit in den übergebenen Signalen.",
                    List.of(), Map.of("pages", String.valueOf(audits.size())));
        }

        String sourceKey = "marketing-seo-audit:" + AgentInputs.key(siteUrl,
                String.join(",", keywords), String.valueOf(audits.size()));
        String html = html(siteUrl, audits);
        AgentWorkspace.DocumentRef document = workspace.ensureDocument(context, sourceKey, null, null,
                "SEO-Audit " + siteUrl, html,
                Map.of("siteUrl", siteUrl, "pages", audits.size(),
                        "findingPages", withFindings.size()),
                List.of());
        if (document.created()) {
            context.addProduced("generated_document", document.id().toString(), "SEO-Audit " + siteUrl);
        }
        int highPriority = (int) withFindings.stream()
                .filter(page -> page.findings().stream().anyMatch(f -> "SOFORT".equals(f.priority())))
                .count();
        String title = "SEO-Maßnahmen umsetzen: " + siteUrl;
        AgentWorkspace.TaskRef task = workspace.ensureTask(context, sourceKey + ":measure", title,
                withFindings.size() + " von " + audits.size() + " Seite(n) mit Befunden, davon "
                        + highPriority + " mit Sofort-Maßnahme. Der Agent ändert nichts an der "
                        + "Website; Umsetzung und Freigabe bleiben menschliche Schritte.",
                highPriority > 0 ? Task.Priority.HIGH : Task.Priority.NORMAL,
                context.now().plus(Duration.ofDays(7)),
                List.of("marketing", "seo"), Map.of("pages", audits.size(),
                        "findingPages", withFindings.size(), "highPriority", highPriority));
        if (task.created()) {
            context.addProduced("task", task.id().toString(), title);
        }
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("pages", String.valueOf(audits.size()));
        meta.put("findingPages", String.valueOf(withFindings.size()));
        meta.put("highPriority", String.valueOf(highPriority));
        meta.put("crawl", "false");
        return AgentResult.done("SEO-Audit für " + siteUrl + ": " + withFindings.size() + " von "
                + audits.size() + " Seite(n) mit Befunden, " + highPriority
                + " Sofort-Maßnahme(n); nichts an der Website geändert.", context.produced(), meta);
    }

    /** Audits the supplied page facts; pure and testable. */
    public static List<PageAudit> audit(List<Map<String, Object>> pages, List<String> keywords) {
        List<PageAudit> audits = new ArrayList<>();
        for (Map<String, Object> page : pages) {
            String url = text(page, "url");
            if (url == null) {
                continue;
            }
            String title = text(page, "title");
            String meta = text(page, "metaDescription");
            Integer h1 = integer(page.get("h1Count"));
            Integer internalLinks = integer(page.get("internalLinks"));
            Integer words = integer(page.get("wordCount"));
            List<Finding> findings = new ArrayList<>();
            if (title == null || title.isBlank()) {
                findings.add(new Finding("Titel fehlt", "SOFORT", "hoch", "gering",
                        "Ohne Titel kann die Seite nicht sinnvoll ranken."));
            }
            else {
                if (title.length() < 30) {
                    findings.add(new Finding("Titel zu kurz (" + title.length() + " Zeichen)",
                            "SOFORT", "hoch", "gering", "Aussagekräftigen Titel mit 30–60 Zeichen setzen."));
                }
                else if (title.length() > 60) {
                    findings.add(new Finding("Titel zu lang (" + title.length() + " Zeichen)",
                            "PLANEN", "mittel", "gering", "Titel auf 60 Zeichen kürzen."));
                }
                String lower = title.toLowerCase(Locale.GERMAN);
                for (String keyword : keywords) {
                    if (!lower.contains(keyword.toLowerCase(Locale.GERMAN))) {
                        findings.add(new Finding("Keyword „" + keyword + "“ fehlt im Titel",
                                "SOFORT", "hoch", "gering", "Keyword natürlich in den Titel aufnehmen."));
                    }
                }
            }
            if (meta == null || meta.isBlank()) {
                findings.add(new Finding("Meta-Beschreibung fehlt", "SOFORT", "hoch", "gering",
                        "Meta-Beschreibung mit 70–160 Zeichen ergänzen."));
            }
            else if (meta.length() > 160) {
                findings.add(new Finding("Meta-Beschreibung zu lang (" + meta.length() + " Zeichen)",
                        "PLANEN", "mittel", "gering", "Auf 160 Zeichen kürzen."));
            }
            if (h1 != null && h1 != 1) {
                findings.add(new Finding("H1-Anzahl " + h1 + " statt genau 1", "SOFORT", "hoch",
                        "mittel", "Genau eine H1 je Seite verwenden."));
            }
            if (internalLinks != null && internalLinks < 2) {
                findings.add(new Finding("Wenige interne Links (" + internalLinks + ")", "PLANEN",
                        "mittel", "mittel", "Auf thematisch passende Seiten verlinken."));
            }
            if (words != null && words < 300) {
                findings.add(new Finding("Sehr wenig Inhalt (" + words + " Wörter)", "PLANEN",
                        "mittel", "hoch", "Inhalt auf mindestens 300 Wörter ausbauen, nur wenn sinnvoll."));
            }
            findings.sort(Comparator.comparing((Finding f) -> !"SOFORT".equals(f.priority()))
                    .thenComparing(Finding::signal));
            audits.add(new PageAudit(url, title, meta, h1 == null ? 0 : h1, findings));
        }
        return List.copyOf(audits);
    }

    private static String html(String siteUrl, List<PageAudit> audits) {
        StringBuilder html = new StringBuilder("<h1>SEO-Audit: ").append(AgentInputs.html(siteUrl))
                .append("</h1><p>Geprüfte Seiten: ").append(audits.size())
                .append(". Grundlage sind die übergebenen Seitendaten, kein Website-Crawl.</p>");
        html.append("<table><tr><th>Seite</th><th>Titel</th><th>Befunde</th><th>Priorität</th></tr>");
        for (PageAudit audit : audits) {
            String worst = audit.findings().stream().anyMatch(f -> "SOFORT".equals(f.priority()))
                    ? "SOFORT" : audit.findings().isEmpty() ? "—" : "PLANEN";
            html.append("<tr><td>").append(AgentInputs.html(audit.url())).append("</td><td>")
                    .append(AgentInputs.html(audit.title())).append("</td><td>")
                    .append(audit.findings().isEmpty() ? "keine"
                            : String.join("<br>", audit.findings().stream().map(Finding::describe).toList()))
                    .append("</td><td>").append(worst).append("</td></tr>");
        }
        html.append("</table><p><em>Der Agent ändert nichts an der Website. Die Maßnahmenliste ist "
                + "ein Vorschlag zur Freigabe.</em></p>");
        return html.toString();
    }

    private static String text(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).strip();
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return AgentInputs.integer(value == null ? null : String.valueOf(value));
    }

    /** One page and its findings. */
    public record PageAudit(String url, String title, String metaDescription, int h1Count,
                            List<Finding> findings) {

        String describe() {
            return String.join("; ", findings.stream().map(Finding::describe).toList());
        }
    }

    /** One signal that is not right yet. */
    public record Finding(String signal, String priority, String impact, String effort,
                          String recommendation) {

        String describe() {
            return signal + " → " + recommendation;
        }
    }
}
