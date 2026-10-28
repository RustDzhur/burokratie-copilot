package de.buerokratiecopilot.agents.support;

import java.time.Duration;
import java.time.Instant;
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
import de.buerokratiecopilot.notifications.domain.Notification;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code support-escalation} — Eskalation.
 *
 * <p>Scores a ticket on the signals the platform actually has — ticket priority, SLA deadline,
 * repeat contacts, tone and customer tier — and proposes an escalation level. The level names who
 * has to take over, not what the answer must be. The agent notifies and files a task; it decides
 * nothing about goodwill or compensation.</p>
 */
@Component
public class SupportEscalationAgent implements Agent {

    private final JdbcTemplate jdbc;
    private final AgentWorkspace workspace;

    public SupportEscalationAgent(JdbcTemplate jdbc, AgentWorkspace workspace) {
        this.jdbc = jdbc;
        this.workspace = workspace;
    }

    @Override
    public String id() {
        return "support-escalation";
    }

    @Override
    public AgentResult run(AgentContext context) {
        UUID ticketId = AgentInputs.uuid(context.input("ticketId"));
        if (ticketId == null) {
            return AgentResult.skipped("Eskalation: 'ticketId' fehlt oder ist keine gültige UUID.");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, title, priority, due_at, created_at
                from tasks
                where tenant_id = ? and id = ? and deleted_at is null
                """, context.tenantId(), ticketId);
        if (rows.isEmpty()) {
            return AgentResult.skipped("Eskalation: Ticket/Aufgabe " + ticketId
                    + " ist im Mandanten nicht vorhanden.");
        }
        Map<String, Object> ticket = rows.getFirst();
        Instant slaDueAt = AgentInputs.instant(context.input("slaDueAt"));
        if (context.input("slaDueAt") != null && !context.input("slaDueAt").isBlank()
                && slaDueAt == null) {
            return AgentResult.failed("Eskalation: 'slaDueAt' ist kein gültiger Zeitpunkt.");
        }
        int repeatCount = AgentInputs.integer(context.input("repeatCount"), 0, 0, 1000);
        String tone = AgentInputs.blankTo(context.input("tone"), "NEUTRAL").toUpperCase(Locale.ROOT);
        String tier = AgentInputs.blankTo(context.input("customerTier"), "STANDARD")
                .toUpperCase(Locale.ROOT);
        String ticketPriority = String.valueOf(ticket.get("priority"));
        Instant dueAt = ticket.get("due_at") instanceof java.sql.Timestamp timestamp
                ? timestamp.toInstant() : slaDueAt;

        Escalation escalation = assess(tone, repeatCount, tier, ticketPriority, dueAt, context.now());
        if (escalation.score() == 0) {
            return AgentResult.skipped("Eskalation: keine Eskalationssignale bei Ticket " + ticketId
                    + " (Priorität " + ticketPriority + ", Ton " + tone + ", Wiederholungen "
                    + repeatCount + ").");
        }
        String title = "Eskalation L" + escalation.level() + ": " + ticket.get("title");
        Instant due = context.now().plus(Duration.ofHours(escalation.responseHours()));
        AgentWorkspace.TaskRef task = workspace.ensureTask(context,
                "support-escalation:" + ticketId + ":L" + escalation.level(), title,
                "Ticket: " + ticket.get("title")
                        + "\nTicket-Priorität: " + ticketPriority
                        + "\nSLA-Frist: " + (dueAt == null ? "keine" : dueAt)
                        + "\nTonlage: " + tone + ", Wiederholungen: " + repeatCount
                        + ", Kundensegment: " + tier
                        + "\n\nEskalationsstufe " + escalation.level() + " ("
                        + escalation.responsibility() + "). Signale: "
                        + String.join(", ", escalation.reasons())
                        + "\nDer Agent entscheidet nicht über Kulanz; Übergabe und Antwort bleiben "
                        + "menschliche Schritte.",
                escalation.level() >= 3 ? Task.Priority.CRITICAL : Task.Priority.HIGH,
                due, List.of("support", "escalation", "level-" + escalation.level()),
                Map.of("ticketId", ticketId.toString(), "level", escalation.level(),
                        "score", escalation.score()));
        if (task.created()) {
            context.addProduced("task", task.id().toString(), title);
        }
        boolean notified = workspace.notify(context, context.actorUserId(), "SUPPORT_ESCALATION",
                Notification.Category.TASK,
                escalation.level() >= 3 ? Notification.Severity.CRITICAL
                        : Notification.Severity.WARNING,
                "Eskalation L" + escalation.level() + ": " + ticket.get("title"),
                String.join("; ", escalation.reasons()), "task", task.id(),
                "support-escalation:" + ticketId);
        if (notified) {
            context.addProduced("notification", task.id().toString(), "Eskalationshinweis");
        }
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("ticketId", ticketId.toString());
        meta.put("level", String.valueOf(escalation.level()));
        meta.put("score", String.valueOf(escalation.score()));
        meta.put("responsibility", escalation.responsibility());
        meta.put("reasons", String.join(", ", escalation.reasons()));
        meta.put("notified", String.valueOf(notified));
        return AgentResult.done("Ticket " + ticketId + " eskaliert auf Stufe " + escalation.level()
                + " (" + escalation.responsibility() + "): " + String.join("; ", escalation.reasons())
                + ".", context.produced(), meta);
    }

    /**
     * Deterministic escalation score. SLA and explicit tone weigh most, priority and repeats add.
     * Visible for tests.
     */
    public static Escalation assess(String tone, int repeatCount, String tier, String ticketPriority,
                                    Instant dueAt, Instant now) {
        int score = 0;
        List<String> reasons = new java.util.ArrayList<>();
        if (dueAt != null) {
            if (dueAt.isBefore(now)) {
                score += 3;
                reasons.add("SLA-Frist überschritten");
            }
            else if (dueAt.isBefore(now.plus(Duration.ofHours(2)))) {
                score += 2;
                reasons.add("SLA-Frist in weniger als 2 Stunden");
            }
        }
        if ("ESKALIEREND".equals(tone)) {
            score += 3;
            reasons.add("eskalierende Tonlage");
        }
        else if ("UNZUFRIEDEN".equals(tone)) {
            score += 1;
            reasons.add("unzufriedene Tonlage");
        }
        if (repeatCount >= 3) {
            score += 2;
            reasons.add(repeatCount + " Wiederholungen");
        }
        else if (repeatCount >= 2) {
            score += 1;
            reasons.add(repeatCount + " Wiederholungen");
        }
        if (List.of("VIP", "GOLD", "PLATIN", "STRATEGIC").contains(tier)) {
            score += 2;
            reasons.add("Kundensegment " + tier);
        }
        if ("CRITICAL".equals(ticketPriority)) {
            score += 2;
            reasons.add("Ticket-Priorität kritisch");
        }
        else if ("HIGH".equals(ticketPriority)) {
            score += 1;
            reasons.add("Ticket-Priorität hoch");
        }
        int level = score >= 5 ? 3 : score >= 3 ? 2 : score >= 1 ? 1 : 0;
        String responsibility = switch (level) {
            case 3 -> "Geschäftsführung / Bereitschaft";
            case 2 -> "Bereichsleitung";
            case 1 -> "Teamleitung";
            default -> "keine Eskalation";
        };
        int hours = switch (level) {
            case 3 -> 1;
            case 2 -> 2;
            case 1 -> 4;
            default -> 0;
        };
        return new Escalation(level, score, List.copyOf(reasons), responsibility, hours);
    }

    /** One escalation proposal. */
    public record Escalation(int level, int score, List<String> reasons, String responsibility,
                             int responseHours) {
    }
}
