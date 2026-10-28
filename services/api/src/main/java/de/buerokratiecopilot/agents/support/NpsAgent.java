package de.buerokratiecopilot.agents.support;

import java.time.Duration;
import java.time.YearMonth;
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
 * {@code support-nps} — NPS-Umfragen.
 *
 * <p>Evaluates NPS responses supplied as JSON: promoters (9–10), passives (7–8) and detractors
 * (0–6), the NPS value, and a keyword grouping of free-text answers. Below the configured minimum
 * number of responses the agent refuses to publish a number — an NPS on four answers is noise.
 * Comments are grouped without customer names and nothing is sent.</p>
 */
@Component
public class NpsAgent implements Agent {

    private static final Map<String, List<String>> THEMES = Map.of(
            "Preis", List.of("preis", "teuer", "kosten", "rechnung", "rabatt"),
            "Service", List.of("service", "beratung", "freundlich", "hilfe", "support"),
            "Qualität", List.of("qualität", "qualitaet", "mangel", "defekt", "haltbar"),
            "Lieferung", List.of("lieferung", "versand", "paket", "spät", "spaet", "verpackung"),
            "Kommunikation", List.of("kommunikation", "antwort", "erreichbar", "rückmeldung",
                    "rueckmeldung", "telefon"),
            "Abwicklung", List.of("bestellung", "abwicklung", "retoure", "erstattung", "vertrag"));

    private final ObjectMapper mapper;
    private final AgentWorkspace workspace;

    public NpsAgent(ObjectMapper mapper, AgentWorkspace workspace) {
        this.mapper = mapper;
        this.workspace = workspace;
    }

    @Override
    public String id() {
        return "support-nps";
    }

    @Override
    public AgentResult run(AgentContext context) {
        String period = AgentInputs.clean(context.input("surveyPeriod"));
        if (period == null) {
            return AgentResult.skipped("NPS: 'surveyPeriod' (YYYY-MM) fehlt.");
        }
        try {
            YearMonth.parse(period);
        }
        catch (RuntimeException ex) {
            return AgentResult.failed("NPS: 'surveyPeriod' muss im Format YYYY-MM angegeben werden.");
        }
        int minResponses = AgentInputs.integer(context.input("minResponses"), 10, 1, 10_000);
        String raw = context.input("responses");
        if (raw == null || raw.isBlank()) {
            return AgentResult.skipped("NPS: keine Umfrageantworten übergeben — ohne Rückläufe "
                    + "gibt es keine Auswertung.");
        }
        List<Map<String, Object>> rows;
        try {
            rows = mapper.readValue(raw, new TypeReference<List<Map<String, Object>>>() {
            });
        }
        catch (Exception ex) {
            return AgentResult.failed("NPS: 'responses' ist kein gültiges JSON-Array.");
        }
        List<Response> responses = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Integer score = integer(row.get("score"));
            if (score == null || score < 0 || score > 10) {
                continue;
            }
            responses.add(new Response(score, text(row.get("comment")),
                    text(row.get("customer")), text(row.get("segment"))));
        }
        if (responses.size() < minResponses) {
            return AgentResult.skipped("NPS: nur " + responses.size()
                    + " gültige Antwort(en) — die Mindestzahl von " + minResponses
                    + " ist nicht erreicht; es wird bewusst kein NPS veröffentlicht.");
        }
        Result result = evaluate(responses);
        String segment = AgentInputs.blankTo(context.input("customerSegment"), "alle Kunden");
        String question = AgentInputs.blankTo(context.input("questionText"),
                "Wie wahrscheinlich ist es, dass Sie uns weiterempfehlen?");
        String sourceKey = "support-nps:" + AgentInputs.key(period, segment, raw);
        String html = html(period, segment, question, result);
        AgentWorkspace.DocumentRef document = workspace.ensureDocument(context, sourceKey, null, null,
                "NPS-Auswertung " + period, html,
                Map.of("period", period, "segment", segment, "responses", responses.size(),
                        "nps", result.nps()), List.of());
        if (document.created()) {
            context.addProduced("generated_document", document.id().toString(),
                    "NPS-Auswertung " + period);
        }
        boolean taskCreated = false;
        if (result.nps() < 30 || result.detractors() > result.promoters()) {
            AgentWorkspace.TaskRef task = workspace.ensureTask(context, sourceKey + ":action",
                    "NPS-Maßnahmen prüfen: " + period,
                    "NPS " + result.nps() + " bei " + responses.size() + " Antwort(en): "
                            + result.detractors() + " Kritiker, " + result.passives() + " Passive, "
                            + result.promoters() + " Promotoren. Häufigste Themen: "
                            + result.themes().stream()
                                    .map(t -> t.theme() + " (" + t.count() + ")")
                                    .reduce((a, b) -> a + ", " + b).orElse("keine") + ".",
                    Task.Priority.NORMAL, context.now().plus(Duration.ofDays(7)),
                    List.of("support", "nps"), Map.of("nps", result.nps(),
                            "responses", responses.size()));
            taskCreated = task.created();
            if (taskCreated) {
                context.addProduced("task", task.id().toString(), "NPS-Maßnahmen prüfen");
            }
        }
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("responses", String.valueOf(responses.size()));
        meta.put("promoters", String.valueOf(result.promoters()));
        meta.put("passives", String.valueOf(result.passives()));
        meta.put("detractors", String.valueOf(result.detractors()));
        meta.put("nps", String.valueOf(result.nps()));
        meta.put("taskCreated", String.valueOf(taskCreated));
        meta.put("sent", "false");
        return AgentResult.done("NPS " + period + ": " + result.nps() + " bei " + responses.size()
                + " Antwort(en) — " + result.promoters() + " Promotoren, " + result.passives()
                + " Passive, " + result.detractors() + " Kritiker; nichts versendet.",
                context.produced(), meta);
    }

    /** NPS arithmetic and free-text grouping; pure and testable. */
    public static Result evaluate(List<Response> responses) {
        int promoters = 0;
        int passives = 0;
        int detractors = 0;
        Map<String, Integer> themeCounts = new LinkedHashMap<>();
        Map<String, List<String>> themeSamples = new LinkedHashMap<>();
        for (Response response : responses) {
            if (response.score() >= 9) {
                promoters++;
            }
            else if (response.score() >= 7) {
                passives++;
            }
            else {
                detractors++;
            }
            if (response.comment() != null && !response.comment().isBlank()) {
                String lower = response.comment().toLowerCase(Locale.GERMAN);
                for (Map.Entry<String, List<String>> theme : THEMES.entrySet()) {
                    if (theme.getValue().stream().anyMatch(lower::contains)) {
                        themeCounts.merge(theme.getKey(), 1, Integer::sum);
                        themeSamples.computeIfAbsent(theme.getKey(), key -> new ArrayList<>())
                                .add(response.comment());
                    }
                }
            }
        }
        int total = responses.size();
        int nps = total == 0 ? 0
                : (int) Math.round((promoters - detractors) * 100.0 / total);
        List<Theme> themes = themeCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(entry -> new Theme(entry.getKey(), entry.getValue(),
                        themeSamples.getOrDefault(entry.getKey(), List.of()).stream().limit(3)
                                .map(NpsAgent::anonymize).toList()))
                .toList();
        return new Result(promoters, passives, detractors, nps, themes);
    }

    private static String anonymize(String comment) {
        String value = comment.strip();
        value = value.replaceAll("[\\w.+-]+@[\\w.-]+", "[E-Mail entfernt]");
        value = value.replaceAll("\\+?\\d[\\d /()-]{6,}\\d", "[Telefon entfernt]");
        return value.length() <= 180 ? value : value.substring(0, 177) + "...";
    }

    private static String html(String period, String segment, String question, Result result) {
        StringBuilder html = new StringBuilder("<h1>NPS-Auswertung ").append(period).append("</h1>")
                .append("<p>Segment: ").append(AgentInputs.html(segment))
                .append("<br>Frage: ").append(AgentInputs.html(question))
                .append("<br>Antworten: ").append(result.promoters() + result.passives()
                        + result.detractors())
                .append("<br><strong>NPS: ").append(result.nps()).append("</strong></p>")
                .append("<p>Promotoren: ").append(result.promoters())
                .append(", Passive: ").append(result.passives())
                .append(", Kritiker: ").append(result.detractors()).append("</p>");
        html.append("<h2>Themen der Freitextantworten</h2><table><tr><th>Thema</th><th>Nennungen</th>"
                + "<th>Beispiele (anonymisiert)</th></tr>");
        for (Theme theme : result.themes()) {
            html.append("<tr><td>").append(AgentInputs.html(theme.theme())).append("</td><td>")
                    .append(theme.count()).append("</td><td>")
                    .append(AgentInputs.html(String.join(" | ", theme.samples()))).append("</td></tr>");
        }
        html.append("</table><p><em>Keine Kundennamen, keine externen Profile. Auswertung nur für "
                + "den angegebenen Zweck.</em></p>");
        return html.toString();
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return AgentInputs.integer(value == null ? null : String.valueOf(value));
    }

    private static String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).strip();
    }

    /** One survey answer. */
    public record Response(int score, String comment, String customer, String segment) {
    }

    /** One grouped theme. */
    public record Theme(String theme, int count, List<String> samples) {
    }

    /** The evaluation. */
    public record Result(int promoters, int passives, int detractors, int nps, List<Theme> themes) {
    }
}
