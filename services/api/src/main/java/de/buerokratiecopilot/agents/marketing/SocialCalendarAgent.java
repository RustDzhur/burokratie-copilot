package de.buerokratiecopilot.agents.marketing;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
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
import de.buerokratiecopilot.calendar.CalendarEventService;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code marketing-social-calendar} — Social-Media-Redaktionsplan.
 *
 * <p>Builds a dated editorial plan from the requested period, channels and topic frame. Two posts
 * per channel and week, alternating formats, each with channel, topic, core message and an explicit
 * {@code ENTWURF} approval state. The plan is stored as a generated document and each slot becomes
 * a calendar entry so the team can see it; nothing is published.</p>
 */
@Component
public class SocialCalendarAgent implements Agent {

    private static final int MAX_POSTS = 100;
    private static final List<String> DEFAULT_CHANNELS = List.of("LinkedIn", "Instagram");
    private static final List<String> DEFAULT_TOPICS = List.of("Fachwissen", "Produktvorstellung",
            "Hinter den Kulissen", "Kundenstimme");
    private static final List<String> FORMATS = List.of("Textbeitrag", "Bildbeitrag", "Kurzvideo");

    private final AgentWorkspace workspace;

    public SocialCalendarAgent(AgentWorkspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public String id() {
        return "marketing-social-calendar";
    }

    @Override
    public AgentResult run(AgentContext context) {
        LocalDate start = AgentInputs.date(context.input("startDate"));
        if (start == null) {
            return AgentResult.skipped("Social-Redaktionsplan: 'startDate' (YYYY-MM-DD) fehlt oder "
                    + "ist ungültig.");
        }
        int weeks = AgentInputs.integer(context.input("weeks"), 4, 1, 12);
        List<String> channels = AgentInputs.list(context.input("channels"));
        if (channels.isEmpty()) {
            channels = DEFAULT_CHANNELS;
        }
        if (channels.size() > 8) {
            return AgentResult.failed("Social-Redaktionsplan: höchstens 8 Kanäle werden geplant.");
        }
        List<String> topics = AgentInputs.list(context.input("topics"));
        boolean topicsGiven = !topics.isEmpty();
        if (!topicsGiven) {
            topics = DEFAULT_TOPICS;
        }
        String tone = AgentInputs.blankTo(context.input("tone"), "sachlich, nutzenorientiert");
        List<Post> posts = plan(start, weeks, channels, topics, tone);
        if (posts.isEmpty()) {
            return AgentResult.skipped("Social-Redaktionsplan: keine Planposition erzeugt.");
        }
        String sourceKey = "marketing-social-calendar:" + AgentInputs.key(start.toString(),
                String.valueOf(weeks), String.join(",", channels), String.join(",", topics));

        String html = html(start, weeks, channels, tone, topicsGiven, posts);
        AgentWorkspace.DocumentRef document = workspace.ensureDocument(context, sourceKey, null, null,
                "Social-Redaktionsplan ab " + start, html,
                Map.of("startDate", start.toString(), "weeks", weeks, "posts", posts.size(),
                        "topicsGiven", topicsGiven), List.of());
        if (document.created()) {
            context.addProduced("generated_document", document.id().toString(),
                    "Redaktionsplan ab " + start);
        }
        int events = 0;
        for (Post post : posts) {
            AgentWorkspace.EventRef event = workspace.ensureEvent(context,
                    "Social: " + post.channel() + " — " + post.topic(), post.date(),
                    CalendarEventService.Kind.APPOINTMENT, null,
                    "Entwurf (" + post.status() + "), Format: " + post.format()
                            + ", Kernaussage: " + post.coreMessage());
            if (event.created()) {
                events++;
            }
        }
        String title = "Social-Redaktionsplan freigeben (ab " + start + ")";
        AgentWorkspace.TaskRef task = workspace.ensureTask(context, sourceKey + ":approval", title,
                posts.size() + " Beiträge in " + weeks + " Woche(n) auf " + String.join(", ", channels)
                        + ". Alle Beiträge sind Entwürfe; Freigabe und Veröffentlichung erfolgen "
                        + "durch die Redaktion. Der Agent veröffentlicht nichts.",
                Task.Priority.NORMAL, start.minusDays(3).atStartOfDay(AgentInputs.BERLIN).toInstant(),
                List.of("marketing", "social"), Map.of("posts", posts.size(),
                        "weeks", weeks, "channels", channels.size()));
        if (task.created()) {
            context.addProduced("task", task.id().toString(), title);
        }
        for (Post post : posts.stream().limit(10).toList()) {
            context.addProduced("social-post", post.date() + "/" + post.channel(),
                    post.topic() + " — " + post.format() + " (" + post.status() + ")");
        }
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("posts", String.valueOf(posts.size()));
        meta.put("weeks", String.valueOf(weeks));
        meta.put("channels", String.valueOf(channels.size()));
        meta.put("calendarEntriesCreated", String.valueOf(events));
        meta.put("topicsGiven", String.valueOf(topicsGiven));
        return AgentResult.done("Social-Redaktionsplan ab " + start + ": " + posts.size()
                + " Beitragsentwürfe auf " + channels.size() + " Kanal/Kanälen, "
                + events + " Kalendereintrag/Kalendereinträge; nichts veröffentlicht.",
                context.produced(), meta);
    }

    /** The plan as data; pure and testable. */
    public static List<Post> plan(LocalDate start, int weeks, List<String> channels,
                                  List<String> topics, String tone) {
        List<Post> posts = new ArrayList<>();
        LocalDate monday = start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        int topicIndex = 0;
        for (int week = 0; week < weeks; week++) {
            LocalDate weekStart = monday.plusWeeks(week);
            for (String channel : channels) {
                for (int slot = 0; slot < 2; slot++) {
                    if (posts.size() >= MAX_POSTS) {
                        return List.copyOf(posts);
                    }
                    LocalDate date = weekStart.plusDays(slot == 0 ? 1 : 3); // Tuesday / Thursday
                    String topic = topics.get(topicIndex++ % topics.size());
                    String format = FORMATS.get((week + slot + channel.length()) % FORMATS.size());
                    String message = switch (format) {
                        case "Kurzvideo" -> "Ein konkreter Nutzen in 30 Sekunden: " + topic;
                        case "Bildbeitrag" -> "Ein Blick hinter die Kulissen zu " + topic;
                        default -> "Drei konkrete Punkte zu " + topic + " für die Zielgruppe";
                    };
                    posts.add(new Post(date, channel, format, topic, message, tone, "ENTWURF"));
                }
            }
        }
        return List.copyOf(posts);
    }

    private static String html(LocalDate start, int weeks, List<String> channels, String tone,
                               boolean topicsGiven, List<Post> posts) {
        StringBuilder html = new StringBuilder("<h1>Social-Media-Redaktionsplan ab ")
                .append(start).append("</h1><p>Zeitraum: ").append(weeks)
                .append(" Woche(n), Kanäle: ").append(AgentInputs.html(String.join(", ", channels)))
                .append(", Tonalität: ").append(AgentInputs.html(tone)).append("</p>");
        if (!topicsGiven) {
            html.append("<p><strong>Hinweis:</strong> Es wurden keine Themen übergeben; die "
                    + "Themenrahmen sind Platzhalter und vor der Produktion zu ersetzen.</p>");
        }
        html.append("<table><tr><th>Datum</th><th>Kanal</th><th>Format</th><th>Thema</th>"
                + "<th>Kernaussage</th><th>Status</th></tr>");
        for (Post post : posts) {
            html.append("<tr><td>").append(post.date()).append("</td><td>")
                    .append(AgentInputs.html(post.channel())).append("</td><td>")
                    .append(AgentInputs.html(post.format())).append("</td><td>")
                    .append(AgentInputs.html(post.topic())).append("</td><td>")
                    .append(AgentInputs.html(post.coreMessage())).append("</td><td>")
                    .append(post.status()).append("</td></tr>");
        }
        html.append("</table><p><em>Alle Beiträge sind Entwürfe. Veröffentlichung nur nach Freigabe "
                + "durch die Redaktion; der Agent hat keinen Kanalzugang.</em></p>");
        return html.toString();
    }

    /** One planned post. */
    public record Post(LocalDate date, String channel, String format, String topic,
                       String coreMessage, String tone, String status) {
    }
}
