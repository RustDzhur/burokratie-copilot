package de.buerokratiecopilot.agents.support;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentTaskFactory;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code support-faq-suggest} — FAQ-Vorschläge.
 *
 * <p>Clusters the support tasks created from incoming requests by the words in their subject and
 * description, counts recurrence and drafts one FAQ entry per recurring theme. The clustering is a
 * deterministic rule — no external AI and no network — and the answer draft is a starting point,
 * not a published knowledge-base article.</p>
 *
 * <p>There is no knowledge-base table, so each suggestion becomes one editorial task. Nothing is
 * published automatically; the task states that a person has to edit and release the text.</p>
 */
@Component
public class SupportFaqSuggestAgent implements Agent {

    private static final int MAX_TASKS = 2000;
    private static final int MAX_CANDIDATES = 10;
    private static final int MAX_TASKS_PER_CANDIDATE = 5;
    private static final Set<String> STOPWORDS = Set.of(
            "bitte", "danke", "und", "oder", "der", "die", "das", "den", "dem", "des", "ein",
            "eine", "einer", "eines", "ist", "sind", "wird", "werden", "kann", "können", "nicht",
            "kein", "keine", "noch", "schon", "auch", "aber", "für", "fur", "mit", "von", "vom",
            "bei", "aus", "auf", "zur", "zum", "im", "in", "an", "am", "um", "zu", "ich", "wir",
            "sie", "es", "hat", "haben", "wurde", "wurden", "sich", "sehr", "uns", "unser",
            "unsere", "mein", "meine", "support", "anfrage", "problem", "frage", "hilfe");

    private final JdbcTemplate jdbc;
    private final AgentTaskFactory tasks;
    private final ObjectMapper mapper;

    public SupportFaqSuggestAgent(JdbcTemplate jdbc, AgentTaskFactory tasks, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tasks = tasks;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return "support-faq-suggest";
    }

    /** One support request after normalisation. */
    public record Ticket(String id, String title, String description, String topic,
                         Instant createdAt) {
    }

    /** A recurring theme and its example tickets. */
    public record Candidate(String theme, String question, String draftAnswer,
                            int occurrences, List<String> examples) {
    }

    @Override
    public AgentResult run(AgentContext context) {
        LocalDate from;
        LocalDate to;
        try {
            from = LocalDate.parse(require(context.input("periodFrom")));
            to = LocalDate.parse(require(context.input("periodTo")));
        }
        catch (RuntimeException ex) {
            return AgentResult.skipped("periodFrom und periodTo im Format YYYY-MM-DD sind "
                    + "erforderlich; ohne Zeitraum keine FAQ-Auswertung.");
        }
        if (to.isBefore(from)) {
            return AgentResult.failed("Das Ende liegt vor dem Anfang.");
        }
        int minOccurrences = integer(context.input("minOccurrences"), 3);
        if (minOccurrences < 1 || minOccurrences > 1000) {
            return AgentResult.failed("minOccurrences muss zwischen 1 und 1000 liegen.");
        }
        Set<String> categories = split(context.input("categories"));

        List<Map<String, Object>> raw = jdbc.queryForList("""
                select id, title, description, created_at, tags::text as tags
                from tasks
                where tenant_id = ? and deleted_at is null
                  and (title like 'Support:%' or tags @> '["support"]'::jsonb)
                  and created_at >= ? and created_at < ?
                order by created_at
                limit ?
                """, context.tenantId(),
                java.sql.Timestamp.from(from.atStartOfDay(ZoneId.of("Europe/Berlin")).toInstant()),
                java.sql.Timestamp.from(to.plusDays(1).atStartOfDay(
                        ZoneId.of("Europe/Berlin")).toInstant()),
                MAX_TASKS);

        List<Ticket> tickets = new ArrayList<>();
        for (Map<String, Object> row : raw) {
            String title = clean(string(row.get("title")));
            if (title == null) {
                continue;
            }
            String topic = topic(string(row.get("tags")));
            if (categories != null && (topic == null
                    || !categories.contains(topic.toLowerCase(Locale.ROOT)))) {
                continue;
            }
            Instant createdAt = instant(row.get("created_at"));
            tickets.add(new Ticket(string(row.get("id")), title,
                    clean(string(row.get("description"))), topic, createdAt));
        }
        if (tickets.isEmpty()) {
            return AgentResult.skipped("Keine Support-Anfragen im Zeitraum " + from + " bis " + to
                    + " gefunden; es gibt keine Grundlage für FAQ-Vorschläge.");
        }
        List<Candidate> candidates = suggest(tickets, minOccurrences);
        if (candidates.isEmpty()) {
            return AgentResult.skipped("Kein Thema kommt mindestens " + minOccurrences
                    + "-mal vor; es wurde kein FAQ-Vorschlag erzeugt.");
        }

        int created = 0;
        List<String> taskIds = new ArrayList<>();
        for (Candidate candidate : candidates.stream().limit(MAX_CANDIDATES).toList()) {
            AgentTaskFactory.Created task = tasks.createIfAbsent(context.tenantId(),
                    context.actorUserId(), "FAQ-Entwurf: " + candidate.theme(),
                    taskDescription(candidate, from, to), Task.Priority.LOW,
                    "Wiederkehrende Support-Frage; der Entwurf muss redaktionell geprüft und "
                            + "freigegeben werden, bevor er veröffentlicht wird.",
                    context.now().plus(14, ChronoUnit.DAYS),
                    List.of("support", "faq", candidate.theme()));
            if (task.created()) {
                created++;
            }
            taskIds.add(task.taskId().toString());
            context.addProduced("faq-candidate", candidate.theme(),
                    candidate.occurrences() + " Vorkommen; " + candidate.question());
        }

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("periodFrom", from.toString());
        meta.put("periodTo", to.toString());
        meta.put("tickets", String.valueOf(tickets.size()));
        meta.put("candidates", String.valueOf(candidates.size()));
        meta.put("minOccurrences", String.valueOf(minOccurrences));
        meta.put("tasksCreated", String.valueOf(created));
        meta.put("taskIds", String.join(",", taskIds));
        meta.put("limitation", "Die Clusterbildung ist regelbasiert (Wortüberlappung), keine "
                + "KI. Es gibt keine Wissensbasis-Tabelle; Vorschläge werden als Aufgaben "
                + "vorgelegt, nicht veröffentlicht.");

        return AgentResult.done("FAQ-Vorschläge aus " + tickets.size() + " Support-Anfragen: "
                        + candidates.size() + " Thema(en) mit mindestens " + minOccurrences
                        + " Vorkommen; " + created + " neue Entwurfsaufgabe(n). Nichts veröffentlicht.",
                context.produced(), meta);
    }

    /**
     * Greedy word-overlap clustering. Visible for tests.
     *
     * <p>Each ticket joins the existing cluster with the largest keyword overlap; without overlap it
     * opens a new cluster named after its longest significant word. That is deliberately simple and
     * explainable: a reviewer can see why two requests ended up together.</p>
     */
    public static List<Candidate> suggest(List<Ticket> tickets, int minOccurrences) {
        List<Cluster> clusters = new ArrayList<>();
        for (Ticket ticket : tickets) {
            Set<String> words = words(ticket.title(), ticket.description());
            if (words.isEmpty()) {
                continue;
            }
            Cluster best = null;
            int bestOverlap = 0;
            for (Cluster cluster : clusters) {
                int overlap = overlap(cluster.words(), words);
                if (overlap > bestOverlap) {
                    bestOverlap = overlap;
                    best = cluster;
                }
            }
            if (best == null) {
                String theme = words.stream().max(Comparator.comparingInt(String::length))
                        .orElse("allgemein");
                best = new Cluster(theme, new LinkedHashSet<>(words));
                clusters.add(best);
            }
            best.words().addAll(words);
            best.tickets().add(ticket);
        }

        List<Candidate> candidates = new ArrayList<>();
        for (Cluster cluster : clusters) {
            if (cluster.tickets().size() < minOccurrences) {
                continue;
            }
            List<Ticket> members = cluster.tickets();
            String question = mostCommonQuestion(members);
            String topic = mostCommonTopic(members);
            candidates.add(new Candidate(cluster.theme(), question,
                    draftAnswer(topic, question), members.size(),
                    members.stream().map(Ticket::title).distinct()
                            .limit(MAX_TASKS_PER_CANDIDATE).toList()));
        }
        candidates.sort(Comparator.comparingInt(Candidate::occurrences).reversed()
                .thenComparing(Candidate::theme));
        return List.copyOf(candidates);
    }

    private static Set<String> words(String title, String description) {
        Set<String> words = new LinkedHashSet<>();
        String text = (title == null ? "" : title) + " " + (description == null ? ""
                : description.length() > 500 ? description.substring(0, 500) : description);
        for (String raw : text.toLowerCase(Locale.GERMAN).split("[^a-zäöüß0-9]+")) {
            String word = raw.strip();
            if (word.length() >= 4 && word.length() <= 40 && !STOPWORDS.contains(word)
                    && !word.matches("\\d+")) {
                words.add(word);
            }
        }
        return words;
    }

    private static int overlap(Set<String> left, Set<String> right) {
        int count = 0;
        for (String word : right) {
            if (left.contains(word)) {
                count++;
            }
        }
        return count;
    }

    private static String mostCommonQuestion(List<Ticket> tickets) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Ticket ticket : tickets) {
            String title = ticket.title().replaceFirst("(?i)^support:\\s*", "").strip();
            counts.merge(title, 1, Integer::sum);
        }
        String question = counts.entrySet().stream()
                .max(Map.Entry.<String, Integer>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
                .map(Map.Entry::getKey).orElse("Wiederkehrende Support-Anfrage");
        if (!question.endsWith("?")) {
            question = question + "?";
        }
        return question.length() > 160 ? question.substring(0, 157) + "..." : question;
    }

    private static String mostCommonTopic(List<Ticket> tickets) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Ticket ticket : tickets) {
            counts.merge(ticket.topic() == null ? "ALLGEMEIN" : ticket.topic(), 1, Integer::sum);
        }
        return counts.entrySet().stream().max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse("ALLGEMEIN");
    }

    private static String draftAnswer(String topic, String question) {
        String steps = switch (topic == null ? "ALLGEMEIN" : topic.toUpperCase(Locale.ROOT)) {
            case "RECHNUNG" -> "1. Rechnungsnummer und Kundennummer bereithalten. "
                    + "2. Zahlungsstatus und Beleg prüfen. 3. Bei Abweichung eine Korrektur "
                    + "veranlassen und den Vorgang dokumentieren.";
            case "TECHNIK" -> "1. Fehlermeldung und betroffenes System notieren. "
                    + "2. Letzten bekannten funktionierenden Stand prüfen. 3. Bei anhaltender "
                    + "Störung den technischen Support mit Screenshot informieren.";
            case "VERTRAG" -> "1. Vertragsnummer und betroffene Frist prüfen. "
                    + "2. Änderungswunsch schriftlich festhalten. 3. Zuständige Stelle "
                    + "informieren und Bestätigung abwarten.";
            default -> "1. Anliegen und betroffenen Vorgang konkretisieren. "
                    + "2. Erforderliche Unterlagen zusammenstellen. 3. Rückmeldung mit "
                    + "nächstem Schritt und Termin geben.";
        };
        return "Entwurf für die Wissensbasis – vor Veröffentlichung redaktionell prüfen.\n\n"
                + "Frage: " + question + "\n\nAntwort:\n" + steps
                + "\n\nBei Rückfragen wenden Sie sich bitte an das Support-Team.";
    }

    private static String taskDescription(Candidate candidate, LocalDate from, LocalDate to) {
        StringBuilder text = new StringBuilder();
        text.append("Wiederkehrende Support-Frage (").append(candidate.occurrences())
                .append(" Vorkommen, ").append(from).append(" bis ").append(to).append(")\n\n")
                .append(candidate.draftAnswer()).append("\n\nBeispiel-Anfragen:\n");
        for (String example : candidate.examples()) {
            text.append("- ").append(example).append('\n');
        }
        text.append("\nBitte Entwurf prüfen, ergänzen und erst dann veröffentlichen.");
        return text.toString();
    }

    private String topic(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) {
            return null;
        }
        try {
            List<String> tags = mapper.readValue(tagsJson, new TypeReference<List<String>>() {
            });
            for (String tag : tags) {
                if (List.of("RECHNUNG", "TECHNIK", "VERTRAG", "ALLGEMEIN")
                        .contains(tag.toUpperCase(Locale.ROOT))) {
                    return tag.toUpperCase(Locale.ROOT);
                }
            }
        }
        catch (Exception ex) {
            return null;
        }
        return null;
    }

    private static Set<String> split(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Set<String> values = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            if (!part.isBlank()) {
                values.add(part.strip().toLowerCase(Locale.ROOT));
            }
        }
        return values.isEmpty() ? null : values;
    }

    private static int integer(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.strip());
        }
        catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static Instant instant(Object raw) {
        if (raw instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (raw instanceof Instant value) {
            return value;
        }
        if (raw == null) {
            return null;
        }
        try {
            return Instant.parse(String.valueOf(raw));
        }
        catch (java.time.DateTimeException ex) {
            return null;
        }
    }

    private static String string(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String require(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing");
        }
        return value.strip();
    }

    /** Mutable cluster used while building; converted to an immutable candidate afterwards. */
    private static final class Cluster {
        private final String theme;
        private final Set<String> words;
        private final List<Ticket> tickets = new ArrayList<>();

        private Cluster(String theme, Set<String> words) {
            this.theme = theme;
            this.words = words;
        }

        private String theme() {
            return theme;
        }

        private Set<String> words() {
            return words;
        }

        private List<Ticket> tickets() {
            return tickets;
        }
    }
}
