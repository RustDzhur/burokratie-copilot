package de.buerokratiecopilot.agents.communication;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.deadlines.DeadlineService;
import de.buerokratiecopilot.deadlines.domain.TaskDeadline;
import de.buerokratiecopilot.documents.dates.DateClassifier;
import de.buerokratiecopilot.documents.fields.FieldValueParser;
import de.buerokratiecopilot.tasks.TaskService;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code reminder} — Erinnerungen.
 *
 * <p>Finds dates in free text (numeric German dates, ISO dates, month written out), classifies each
 * with the production wording classifier ({@link DateClassifier} — payment, objection, appointment,
 * validity, plain deadline) and turns the ones that name an obligation into real objects: a task in
 * the tasks module and a deadline on it through the deadlines module. With {@code autoCreate=false}
 * only the proposals are reported.
 *
 * <p>Idempotent: dates for an existing task are only recorded when the task has no deadline on that
 * day yet; newly created tasks are recognised on a re-run by title, case and due date, so the same
 * text does not create the same task twice. Dates in the past are ignored — an old text replayed
 * must not resurrect its deadlines.</p>
 */
@Component
public class ReminderAgent implements Agent {

    private static final Pattern NUMERIC = Pattern.compile(
            "\\b(\\d{1,2})[.\\-/](\\d{1,2})[.\\-/](\\d{2,4})\\b");
    private static final Pattern ISO = Pattern.compile("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b");
    private static final Pattern WORDS = Pattern.compile(
            "\\b(\\d{1,2})\\.?\\s+(Januar|Jänner|Februar|Feber|März|Maerz|April|Mai|Juni|Juli|"
                    + "August|September|Oktober|October|November|Dezember|"
                    + "Jan|Feb|Mär|Apr|Jun|Jul|Aug|Sep|Sept|Okt|Nov|Dez)\\.?\\s+(\\d{4})\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final int SNIPPET_HALF = 70;
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private final TaskService taskService;
    private final DeadlineService deadlineService;

    public ReminderAgent(TaskService taskService, DeadlineService deadlineService) {
        this.taskService = taskService;
        this.deadlineService = deadlineService;
    }

    /** One detected date: where it stands, what it is and whether it was acted on. */
    private record Found(String isoDate, DateClassifier.Kind kind, double confidence, String snippet,
                         String text) {
    }

    @Override
    public String id() {
        return "reminder";
    }

    @Override
    public AgentResult run(AgentContext context) {
        String text = context.input("text");
        if (text == null || text.isBlank()) {
            return AgentResult.skipped("Kein Text vorhanden: das Feld 'text' fehlt.");
        }

        UUID caseId = parseUuid(context.input("caseId"));
        UUID taskId = parseUuid(context.input("taskId"));
        UUID sourceDocumentId = parseUuid(context.input("sourceDocumentId"));
        boolean autoCreate = !"false".equalsIgnoreCase(context.input("autoCreate"));

        List<Found> found = detect(text, context.now());
        if (found.isEmpty()) {
            return AgentResult.skipped(
                    "Keine Frist oder kein Termin im Text erkannt, die erinnert werden müssen.");
        }

        List<AgentResult.Produced> produced = new ArrayList<>();
        java.util.Set<UUID> recordedTasks = new java.util.HashSet<>();
        int tasksCreated = 0;
        int deadlinesCreated = 0;
        int skipped = 0;

        for (Found date : found) {
            if (!autoCreate) {
                produced.add(new AgentResult.Produced("reminder_proposal", date.isoDate(),
                        labelFor(date.kind()) + " " + date.isoDate()));
                continue;
            }
            try {
                UUID dueTask = taskId;
                if (dueTask == null) {
                    TaskLookup lookup = ensureTask(context, date, caseId, sourceDocumentId);
                    dueTask = lookup.id();
                    if (lookup.created() && recordedTasks.add(dueTask)) {
                        produced.add(new AgentResult.Produced("task", dueTask.toString(),
                                "Aufgabe für Frist angelegt"));
                        tasksCreated++;
                    }
                }
                if (alreadyHasDeadline(dueTask, LocalDate.parse(date.isoDate()))) {
                    produced.add(new AgentResult.Produced("existing", date.isoDate(),
                            "Frist " + date.isoDate() + " besteht bereits"));
                    skipped++;
                    continue;
                }
                TaskDeadline deadline = deadlineService.recordManual(context.tenantId(), caseId,
                        dueTask, LocalDate.parse(date.isoDate()), date.snippet(),
                        context.actorUserId());
                produced.add(new AgentResult.Produced("deadline", deadline.getId().toString(),
                        "Frist " + date.isoDate() + " (" + labelFor(date.kind()) + ")"));
                deadlinesCreated++;
            }
            catch (RuntimeException ex) {
                // One bad date must not lose the others; the failure is reported, not swallowed.
                produced.add(new AgentResult.Produced("failure", date.isoDate(),
                        "Frist " + date.isoDate() + " konnte nicht angelegt werden: "
                                + ex.getMessage()));
                skipped++;
            }
        }

        boolean done = produced.stream().anyMatch(p -> !"failure".equals(p.kind()));
        if (!done) {
            return AgentResult.failed("Keine der erkannten Fristen konnte angelegt werden.");
        }
        String summary = autoCreate
                ? "Erinnerungen: %d Aufgabe(n) angelegt, %d Frist(en) erfasst, %d übersprungen."
                        .formatted(tasksCreated, deadlinesCreated, skipped)
                : "Erinnerungs-Vorschläge: " + found.size() + " Datum/Daten erkannt.";
        Map<String, String> meta = Map.of(
                "detectedDates", String.valueOf(found.size()),
                "tasksCreated", String.valueOf(tasksCreated),
                "deadlinesCreated", String.valueOf(deadlinesCreated),
                "skipped", String.valueOf(skipped),
                "autoCreate", String.valueOf(autoCreate));
        return AgentResult.done(summary, produced, meta);
    }

    /** Finds the dates in the text that name an obligation, deduplicated by day. */
    private List<Found> detect(String text, Instant now) {
        Map<String, Found> byDate = new LinkedHashMap<>();
        for (int[] span : candidates(text)) {
            String candidate = text.substring(span[0], span[1]);
            FieldValueParser.Parsed parsed = FieldValueParser.parse(candidate, "DATE");
            if (!parsed.valid() || parsed.normalized() == null) {
                continue;
            }
            LocalDate day;
            try {
                day = LocalDate.parse(parsed.normalized());
            }
            catch (java.time.format.DateTimeParseException ex) {
                continue;
            }
            if (day.isBefore(LocalDate.ofInstant(now, BERLIN))) {
                continue;
            }
            DateClassifier.Classified classified =
                    DateClassifier.classify(text, span[0], span[1] - span[0]);
            if (!DateClassifier.createAutomatically(classified)) {
                continue;
            }
            byDate.putIfAbsent(parsed.normalized(),
                    new Found(parsed.normalized(), classified.kind(), classified.confidence(),
                            snippetAround(text, span[0], span[1]), candidate));
        }
        return new ArrayList<>(byDate.values());
    }

    /** A task for the date, created once; on a re-run the existing task is recognised and reused. */
    private record TaskLookup(UUID id, boolean created) {
    }

    private TaskLookup ensureTask(AgentContext context, Found date, UUID caseId,
                                  UUID sourceDocumentId) {
        String title = titleFor(date);
        Page<Task> existing = taskService.listVisible(context.tenantId(), context.actorUserId(), true,
                null, null, PageRequest.of(0, 200));
        for (Task task : existing.getContent()) {
            if (task.getSource() == Task.Source.SYSTEM
                    && java.util.Objects.equals(task.getCaseId(), caseId)
                    && sameDay(task.getDueAt(), LocalDate.parse(date.isoDate()))
                    && title.equals(task.getTitle())) {
                return new TaskLookup(task.getId(), false);
            }
        }
        Task created = taskService.create(new TaskService.CreateRequest(
                context.tenantId(), caseId, null, title,
                "Erkannt aus: \"" + date.snippet() + "\" (" + date.text() + ")",
                priorityFor(date.kind()), "RULE", "Datumsangabe im Text als "
                        + date.kind() + " klassifiziert",
                dayAtNoon(LocalDate.parse(date.isoDate())).minusSeconds(86400),
                dayAtNoon(LocalDate.parse(date.isoDate())),
                null, false, null, null, List.of(), Task.Source.SYSTEM, sourceDocumentId),
                context.actorUserId());
        return new TaskLookup(created.getId(), true);
    }

    private boolean alreadyHasDeadline(UUID taskId, LocalDate day) {
        return deadlineService.forTask(taskId).stream()
                .anyMatch(deadline -> day.equals(deadline.getDueDate()));
    }

    private static boolean sameDay(Instant instant, LocalDate day) {
        if (instant == null) {
            return false;
        }
        return day.equals(instant.atZone(BERLIN).toLocalDate());
    }

    private static Instant dayAtNoon(LocalDate day) {
        return day.atTime(12, 0).atZone(BERLIN).toInstant();
    }

    private static String titleFor(Found date) {
        String label = labelFor(date.kind());
        String snippet = date.snippet().replaceAll("\\s+", " ").strip();
        if (snippet.length() > 180) {
            snippet = snippet.substring(0, 180) + "…";
        }
        return label + " " + date.isoDate() + " — " + snippet;
    }

    private static String labelFor(DateClassifier.Kind kind) {
        return switch (kind) {
            case PAYMENT_DEADLINE -> "Zahlung bis";
            case OBJECTION_DEADLINE -> "Antwort bis";
            case APPOINTMENT -> "Termin";
            case VALIDITY_END -> "Gültig bis";
            case DEADLINE -> "Frist";
            case DOCUMENT_DATE, OTHER -> "Datum";
        };
    }

    private static Task.Priority priorityFor(DateClassifier.Kind kind) {
        return switch (kind) {
            case PAYMENT_DEADLINE, OBJECTION_DEADLINE -> Task.Priority.HIGH;
            default -> Task.Priority.NORMAL;
        };
    }

    private static List<int[]> candidates(String text) {
        List<int[]> found = new ArrayList<>();
        Matcher numeric = NUMERIC.matcher(text);
        while (numeric.find()) {
            found.add(new int[] {numeric.start(), numeric.end()});
        }
        Matcher iso = ISO.matcher(text);
        while (iso.find()) {
            found.add(new int[] {iso.start(), iso.end()});
        }
        Matcher words = WORDS.matcher(text);
        while (words.find()) {
            found.add(new int[] {words.start(), words.end()});
        }
        return found;
    }

    private static String snippetAround(String text, int start, int end) {
        int from = Math.max(0, start - SNIPPET_HALF);
        int to = Math.min(text.length(), end + SNIPPET_HALF);
        return text.substring(from, to).replaceAll("\\s+", " ").strip();
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.strip());
        }
        catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
