package de.buerokratiecopilot.agents.support;

import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.tasks.TaskService;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code support-ticket} — Ticket-Erfassung.
 *
 * <p>Classifies an incoming request by deterministic keywords into a topic, a priority and an SLA,
 * then creates one task for the support team. The classification is a rule, so the task records
 * {@code prioritySource=RULE} and the reason; nobody has to guess why a ticket is urgent.</p>
 *
 * <p><strong>No duplicate ticket.</strong> If an open task with the same title exists, the run
 * reports that instead of creating a second one — a mail that arrives twice is one request.</p>
 */
@Component
public class SupportTicketAgent implements Agent {

    static final int MAX_TITLE = 280;

    /** The topics the intake can recognise. */
    public enum Topic {
        RECHNUNG, TECHNIK, VERTRAG, ALLGEMEIN
    }

    /** The outcome of the deterministic triage. */
    public record Triage(Task.Priority priority, Topic topic, int slaMinutes, String rationale) {
    }

    private static final List<String> CRITICAL_WORDS = List.of("ausfall", "komplett", "niemand",
            "frist", "fristgerecht", "anwalt", "kündigung", "kuendigung", "insolvenz", "notfall");
    private static final List<String> HIGH_WORDS = List.of("dringend", "sofort", "fehler",
            "geht nicht", "funktioniert nicht", "rechnung", "mahnung", "frist", "falsch",
            "beschwerde");
    private static final List<String> LOW_WORDS = List.of("allgemeine frage", "information",
            "allgemein", "angebot", "katalog");
    private static final List<String> INVOICE_WORDS = List.of("rechnung", "mahnung", "zahlung",
            "beleg", "gutschrift", "lastschrift");
    private static final List<String> TECH_WORDS = List.of("fehler", "ausfall", "zugang",
            "passwort", "system", "app", "login", "technik", "drucker", "server");
    private static final List<String> CONTRACT_WORDS = List.of("vertrag", "kündigung", "kuendigung",
            "frist", "vereinbarung", "nda");

    private final TaskService tasks;
    private final JdbcTemplate jdbc;

    public SupportTicketAgent(TaskService tasks, JdbcTemplate jdbc) {
        this.tasks = tasks;
        this.jdbc = jdbc;
    }

    @Override
    public String id() {
        return "support-ticket";
    }

    @Override
    public AgentResult run(AgentContext context) {
        String subject = context.input("subject");
        if (subject == null || subject.isBlank()) {
            return AgentResult.skipped("Ohne Betreff lässt sich kein Ticket anlegen.");
        }
        String title = "Support: " + truncate(subject.strip());
        Integer open = jdbc.queryForObject("""
                select count(*) from tasks
                where tenant_id = ? and deleted_at is null and title = ?
                  and status not in ('DONE', 'CANCELLED')
                """, Integer.class, context.tenantId(), title);
        if (open != null && open > 0) {
            return AgentResult.skipped("Zu diesem Anliegen ist bereits eine offene Aufgabe "
                    + "vorhanden; es wurde kein zweites Ticket angelegt.");
        }

        Triage triage = triage(subject, context.input("description"),
                context.input("priorityHint"));
        String channel = blankTo(context.input("channel"), "EMAIL");
        String customer = blankTo(context.input("customerEmail"),
                blankTo(context.input("contactId"), "unbekannt"));
        String description = "Kanal: " + channel + "\nKunde: " + customer + "\n\nAnliegen:\n"
                + blankTo(context.input("description"), subject);
        List<String> tags = new ArrayList<>(List.of("support", triage.topic().name().toLowerCase(Locale.ROOT)));
        if (context.input("externalKey") != null && !context.input("externalKey").isBlank()) {
            tags.add(context.input("externalKey").strip());
        }

        Task ticket = tasks.create(new TaskService.CreateRequest(context.tenantId(), null, null,
                title, description, triage.priority(), "RULE", triage.rationale(),
                context.now().plus(triage.slaMinutes(), ChronoUnit.MINUTES), null,
                "Ticket beantwortet oder an den Fachbereich übergeben", false, null, null,
                List.copyOf(tags), Task.Source.SYSTEM, null), context.actorUserId());

        context.addProduced("task", ticket.getId().toString(),
                title + " (" + triage.priority() + ", " + triage.topic() + ")");
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("ticketId", ticket.getId().toString());
        meta.put("topic", triage.topic().name());
        meta.put("priority", triage.priority().name());
        meta.put("slaMinutes", String.valueOf(triage.slaMinutes()));
        meta.put("channel", channel);
        meta.put("dueAt", DateTimeFormatter.ISO_INSTANT.format(
                context.now().plus(triage.slaMinutes(), ChronoUnit.MINUTES)));
        return AgentResult.done("Ticket erfasst: " + triage.topic() + ", Priorität "
                        + triage.priority() + ", Erstreaktion innerhalb von "
                        + triage.slaMinutes() + " Minuten.",
                context.produced(), meta);
    }

    /**
     * Deterministic triage. The reporter's hint wins over the keywords; otherwise a critical word
     * escalates, a high word raises, and a low word lowers the default. Visible for tests.
     */
    public static Triage triage(String subject, String description, String priorityHint) {
        String text = (subject == null ? "" : subject).toLowerCase(Locale.GERMAN) + " "
                + (description == null ? "" : description).toLowerCase(Locale.GERMAN);
        Task.Priority priority;
        String rationale;
        Task.Priority hinted = parsePriority(priorityHint);
        if (hinted != null && hinted != Task.Priority.INFORMATIONAL) {
            priority = hinted;
            rationale = "Vom Melder angegebene Dringlichkeit: " + hinted.name();
        } else if (containsAny(text, CRITICAL_WORDS)) {
            priority = Task.Priority.CRITICAL;
            rationale = "Kritisches Schlüsselwort im Anliegen (Ausfall, Frist oder rechtliches "
                    + "Risiko)";
        } else if (containsAny(text, HIGH_WORDS)) {
            priority = Task.Priority.HIGH;
            rationale = "Dringliches Schlüsselwort im Anliegen (Fehler, Rechnung oder Frist)";
        } else if (containsAny(text, LOW_WORDS)) {
            priority = Task.Priority.LOW;
            rationale = "Allgemeine Anfrage ohne Dringlichkeitssignal";
        } else {
            priority = Task.Priority.NORMAL;
            rationale = "Kein Dringlichkeitssignal erkannt; Standardbearbeitung";
        }
        Topic topic = containsAny(text, INVOICE_WORDS) ? Topic.RECHNUNG
                : containsAny(text, TECH_WORDS) ? Topic.TECHNIK
                : containsAny(text, CONTRACT_WORDS) ? Topic.VERTRAG : Topic.ALLGEMEIN;
        return new Triage(priority, topic, slaMinutes(priority), rationale);
    }

    static int slaMinutes(Task.Priority priority) {
        return switch (priority) {
            case CRITICAL -> 60;
            case HIGH -> 240;
            case NORMAL -> 1440;
            case LOW -> 4320;
            case INFORMATIONAL -> 10080;
        };
    }

    private static Task.Priority parsePriority(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Task.Priority.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean containsAny(String text, List<String> words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static String truncate(String value) {
        return value.length() <= MAX_TITLE ? value : value.substring(0, MAX_TITLE - 3) + "...";
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
