package de.buerokratiecopilot.agents.support;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentTaskFactory;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code support-sla-report} — SLA-Bericht.
 *
 * <p>Computes first-response and resolution times from the timestamps the support tasks actually
 * carry. The first response is derived from {@code started_at}: the platform has no separate
 * customer-visible response timestamp, and the report says so instead of presenting an
 * approximation as a measured value. Open tickets older than the target count as violations,
 * because an unanswered ticket does not stop being overdue.</p>
 *
 * <p>The report becomes one task for the support lead. It assigns no blame and changes no ticket.</p>
 */
@Component
public class SupportSlaReportAgent implements Agent {

    private static final int MAX_TASKS = 5000;
    private static final int MAX_PRODUCED = 50;
    private static final Pattern CHANNEL = Pattern.compile("(?im)^\\s*Kanal:\\s*([A-Z_]+)");

    private final JdbcTemplate jdbc;
    private final AgentTaskFactory tasks;

    public SupportSlaReportAgent(JdbcTemplate jdbc, AgentTaskFactory tasks) {
        this.jdbc = jdbc;
        this.tasks = tasks;
    }

    @Override
    public String id() {
        return "support-sla-report";
    }

    /** One support ticket with the timestamps the report uses. */
    public record Ticket(UUID id, String title, String channel, String status, Instant createdAt,
                         Instant startedAt, Instant completedAt) {
    }

    /** One SLA breach. */
    public record Violation(UUID ticketId, String title, String channel, String code,
                            String message) {
    }

    /** The computed report. */
    public record Analysis(int tickets, int responded, int resolved, Double avgFirstResponseMinutes,
                           Double avgResolutionHours, List<Violation> violations,
                           Map<String, Integer> byChannel) {
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
                    + "erforderlich; ohne Zeitraum kein SLA-Bericht.");
        }
        if (to.isBefore(from)) {
            return AgentResult.failed("Das Ende liegt vor dem Anfang.");
        }
        int targetResponse = integer(context.input("targetFirstResponseMinutes"), 240);
        int targetResolution = integer(context.input("targetResolutionHours"), 48);
        if (targetResponse < 1 || targetResolution < 1) {
            return AgentResult.failed("Die SLA-Ziele müssen positiv sein.");
        }
        Set<String> channelFilter = split(context.input("channels"));

        List<Map<String, Object>> raw = jdbc.queryForList("""
                select id, title, description, status, created_at, started_at, completed_at
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
            String channel = channel(clean(string(row.get("description"))));
            if (channelFilter != null && !channelFilter.contains(channel.toLowerCase(Locale.ROOT))) {
                continue;
            }
            tickets.add(new Ticket((UUID) row.get("id"), string(row.get("title")), channel,
                    string(row.get("status")), instant(row.get("created_at")),
                    instant(row.get("started_at")), instant(row.get("completed_at"))));
        }
        if (tickets.isEmpty()) {
            return AgentResult.skipped("Keine Support-Tickets im Zeitraum " + from + " bis " + to
                    + (channelFilter == null ? "" : " für Kanäle "
                            + String.join(", ", channelFilter)) + " gefunden.");
        }

        Analysis analysis = analyse(tickets, context.now(), targetResponse, targetResolution);
        String title = "SLA-Bericht " + from + " bis " + to;
        AgentTaskFactory.Created task = tasks.createIfAbsent(context.tenantId(),
                context.actorUserId(), title,
                description(analysis, from, to, targetResponse, targetResolution),
                analysis.violations().isEmpty() ? Task.Priority.LOW : Task.Priority.HIGH,
                "SLA-Auswertung mit " + analysis.violations().size()
                        + " Verletzung(en); der Bericht ist die Grundlage für die "
                        + "Team-Besprechung.",
                context.now().plus(3, ChronoUnit.DAYS),
                List.of("support", "sla"));

        for (Violation violation : analysis.violations().stream().limit(MAX_PRODUCED).toList()) {
            context.addProduced("sla-violation", violation.ticketId().toString(),
                    violation.code() + ": " + violation.title() + " — " + violation.message());
        }
        context.addProduced("sla-report", from + ":" + to,
                analysis.tickets() + " Tickets, " + analysis.violations().size()
                        + " Verletzungen");
        context.addProduced("task", task.taskId().toString(), title);

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("periodFrom", from.toString());
        meta.put("periodTo", to.toString());
        meta.put("tickets", String.valueOf(analysis.tickets()));
        meta.put("responded", String.valueOf(analysis.responded()));
        meta.put("resolved", String.valueOf(analysis.resolved()));
        meta.put("violations", String.valueOf(analysis.violations().size()));
        meta.put("avgFirstResponseMinutes", analysis.avgFirstResponseMinutes() == null ? ""
                : String.format(Locale.ROOT, "%.1f", analysis.avgFirstResponseMinutes()));
        meta.put("avgResolutionHours", analysis.avgResolutionHours() == null ? ""
                : String.format(Locale.ROOT, "%.1f", analysis.avgResolutionHours()));
        meta.put("targetFirstResponseMinutes", String.valueOf(targetResponse));
        meta.put("targetResolutionHours", String.valueOf(targetResolution));
        meta.put("channels", analysis.byChannel().entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + "," + right).orElse(""));
        meta.put("taskId", task.taskId().toString());
        meta.put("taskCreated", String.valueOf(task.created()));
        meta.put("limitation", "Die Erstreaktion wird aus started_at abgeleitet; ein separater "
                + "Zeitstempel der Kundenantwort existiert nicht. Die Werte sind daher eine "
                + "Näherung, keine gemessene Kundenerfahrung.");

        String summary = "SLA-Bericht " + from + " bis " + to + ": " + analysis.tickets()
                + " Tickets, " + analysis.violations().size() + " Verletzung(en), "
                + (analysis.avgFirstResponseMinutes() == null ? "keine"
                        : String.format(Locale.ROOT, "%.0f", analysis.avgFirstResponseMinutes()))
                + " Min. Ø-Erstreaktion, "
                + (analysis.avgResolutionHours() == null ? "keine"
                        : String.format(Locale.ROOT, "%.1f", analysis.avgResolutionHours()))
                + " h Ø-Lösung. Zur Führungskraft vorgelegt.";
        return AgentResult.done(summary, context.produced(), meta);
    }

    /** Computes the report. Visible for tests. */
    public static Analysis analyse(List<Ticket> tickets, Instant now, int targetResponseMinutes,
                                   int targetResolutionHours) {
        List<Violation> violations = new ArrayList<>();
        Map<String, Integer> byChannel = new LinkedHashMap<>();
        long responseSum = 0;
        int responded = 0;
        long resolutionSum = 0;
        int resolved = 0;

        for (Ticket ticket : tickets) {
            byChannel.merge(ticket.channel(), 1, Integer::sum);
            if (ticket.createdAt() != null && ticket.startedAt() != null
                    && ticket.startedAt().isBefore(ticket.createdAt())) {
                // A start before creation is bad data, not a negative response time.
                violations.add(new Violation(ticket.id(), ticket.title(), ticket.channel(),
                        "INVALID_TIMESTAMP",
                        "started_at liegt vor created_at; die Reaktionszeit ist nicht berechenbar"));
            }
            else if (ticket.createdAt() != null && ticket.startedAt() != null) {
                long minutes = Duration.between(ticket.createdAt(), ticket.startedAt()).toMinutes();
                responseSum += minutes;
                responded++;
                if (minutes > targetResponseMinutes) {
                    violations.add(new Violation(ticket.id(), ticket.title(), ticket.channel(),
                            "FIRST_RESPONSE_BREACH",
                            "Erstreaktion nach " + minutes + " Min. (Ziel "
                                    + targetResponseMinutes + " Min.)"));
                }
            }
            else if (ticket.createdAt() != null && !isClosed(ticket)) {
                long minutes = Duration.between(ticket.createdAt(), now).toMinutes();
                if (minutes > targetResponseMinutes) {
                    violations.add(new Violation(ticket.id(), ticket.title(), ticket.channel(),
                            "NO_RESPONSE",
                            "noch keine Erstreaktion, offen seit " + minutes + " Min. (Ziel "
                                    + targetResponseMinutes + " Min.)"));
                }
            }

            if (ticket.createdAt() != null && ticket.completedAt() != null) {
                long hours = Duration.between(ticket.createdAt(), ticket.completedAt()).toHours();
                resolutionSum += hours;
                resolved++;
                if (hours > targetResolutionHours) {
                    violations.add(new Violation(ticket.id(), ticket.title(), ticket.channel(),
                            "RESOLUTION_BREACH",
                            "Lösung nach " + hours + " h (Ziel " + targetResolutionHours + " h)"));
                }
            }
            else if (ticket.createdAt() != null && !isClosed(ticket)) {
                long hours = Duration.between(ticket.createdAt(), now).toHours();
                if (hours > targetResolutionHours) {
                    violations.add(new Violation(ticket.id(), ticket.title(), ticket.channel(),
                            "OPEN_OVERDUE",
                            "offen seit " + hours + " h, Ziel " + targetResolutionHours + " h"));
                }
            }
        }
        return new Analysis(tickets.size(), responded, resolved,
                responded == 0 ? null : responseSum / (double) responded,
                resolved == 0 ? null : resolutionSum / (double) resolved,
                List.copyOf(violations), Map.copyOf(byChannel));
    }

    private static boolean isClosed(Ticket ticket) {
        return "DONE".equalsIgnoreCase(ticket.status())
                || "CANCELLED".equalsIgnoreCase(ticket.status());
    }

    private static String channel(String description) {
        if (description == null) {
            return "UNBEKANNT";
        }
        Matcher matcher = CHANNEL.matcher(description);
        return matcher.find() ? matcher.group(1).toUpperCase(Locale.ROOT) : "UNBEKANNT";
    }

    private static String description(Analysis analysis, LocalDate from, LocalDate to,
                                      int targetResponse, int targetResolution) {
        StringBuilder text = new StringBuilder();
        text.append("SLA-Bericht ").append(from).append(" bis ").append(to).append("\n")
                .append("Tickets: ").append(analysis.tickets()).append('\n')
                .append("Mit Erstreaktion: ").append(analysis.responded()).append('\n')
                .append("Abgeschlossen: ").append(analysis.resolved()).append('\n')
                .append("Ziel Erstreaktion: ").append(targetResponse).append(" Min., Ziel Lösung: ")
                .append(targetResolution).append(" h\n")
                .append("Verletzungen: ").append(analysis.violations().size()).append("\n\n");
        if (!analysis.violations().isEmpty()) {
            text.append("Verletzungen:\n");
            for (Violation violation : analysis.violations().stream().limit(MAX_PRODUCED).toList()) {
                text.append("- [").append(violation.code()).append("] ")
                        .append(violation.title()).append(" (").append(violation.channel())
                        .append("): ").append(violation.message()).append('\n');
            }
        }
        text.append("\nHinweis: Die Erstreaktion ist aus started_at abgeleitet, nicht aus einem ")
                .append("separaten Kunden-Zeitstempel. Der Bericht zeigt Ursachen, keine ")
                .append("Schuldzuweisung.");
        return text.toString();
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
}
