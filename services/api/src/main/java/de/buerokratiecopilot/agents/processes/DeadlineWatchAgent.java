package de.buerokratiecopilot.agents.processes;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.notifications.domain.Notification;
import de.buerokratiecopilot.orchestration.OwnerNotifier;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.settings.SettingsService;

/**
 * {@code deadline-watch} — Fristen-Überwachung.
 *
 * <p>Scans the active deadlines of the tenant (deadlines module), escalates overdue ones and reminds
 * about approaching ones through the notification channel. The same dedup keys the watchdog of the
 * orchestration module uses ({@code deadline-reminder-<id>}) make repeated runs and both watchers
 * mutually idempotent: one deadline is reported once, however often the agent runs. Unverified
 * deadlines are counted and reported, not silently accepted.</p>
 */
@Component
public class DeadlineWatchAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(DeadlineWatchAgent.class);

    private final JdbcTemplate jdbc;
    private final OwnerNotifier notifier;
    private final SettingsService settings;

    public DeadlineWatchAgent(JdbcTemplate jdbc, OwnerNotifier notifier, SettingsService settings) {
        this.jdbc = jdbc;
        this.notifier = notifier;
        this.settings = settings;
    }

    @Override
    public String id() {
        return "deadline-watch";
    }

    @Override
    public AgentResult run(AgentContext context) {
        return RequestContext.runAs(context.tenantId(), () -> doRun(context));
    }

    private AgentResult doRun(AgentContext context) {
        UUID tenantId = context.tenantId();
        int leadDays = parseLeadDays(context.input("leadDays"));
        boolean includeVerification = parseIncludeVerification(context.input("includeVerification"));

        List<DeadlineRow> dueSoon = loadDueSoon(tenantId, leadDays);
        List<DeadlineRow> overdue = loadOverdue(tenantId);

        List<AgentResult.Produced> produced = new ArrayList<>();
        for (DeadlineRow deadline : overdue) {
            remind(tenantId, deadline, Notification.Category.MISSED_DEADLINE,
                    Notification.Severity.WARNING, "task.deadline_overdue", "Frist überfällig",
                    "Die Frist für „" + deadline.title() + "“ ist am " + deadline.dueDate()
                            + " abgelaufen.",
                    "deadline-overdue-" + deadline.deadlineId());
            produced.add(new AgentResult.Produced("deadline_escalation",
                    deadline.deadlineId().toString(),
                    "Überfällige Frist eskaliert: " + deadline.title()));
        }
        for (DeadlineRow deadline : dueSoon) {
            remind(tenantId, deadline, Notification.Category.DEADLINE, Notification.Severity.WARNING,
                    "task.deadline_approaching", "Frist naht",
                    "Die Frist für „" + deadline.title() + "“ läuft am " + deadline.dueDate()
                            + " ab.",
                    "deadline-reminder-" + deadline.deadlineId());
            produced.add(new AgentResult.Produced("deadline_reminder",
                    deadline.deadlineId().toString(),
                    "Nahende Frist gemeldet: " + deadline.title()));
        }

        long unverified = includeVerification ? countUnverified(tenantId) : -1;

        Map<String, String> meta = new LinkedHashMap<>(Map.of(
                "leadDays", String.valueOf(leadDays),
                "overdueEscalated", String.valueOf(overdue.size()),
                "dueSoonReminded", String.valueOf(dueSoon.size()),
                "unverifiedDeadlines", String.valueOf(unverified),
                "checkedAt", context.now().toString()));

        if (produced.isEmpty() && unverified <= 0) {
            return new AgentResult(false,
                    "Keine überfälligen oder nahenden Fristen — es gibt nichts zu eskalieren",
                    List.of(), meta);
        }
        List<String> parts = new ArrayList<>();
        if (!overdue.isEmpty()) {
            parts.add(overdue.size() + " Frist(en) überfällig und eskaliert");
        }
        if (!dueSoon.isEmpty()) {
            parts.add(dueSoon.size() + " nahe Frist(en) gemeldet");
        }
        if (unverified > 0) {
            parts.add(unverified + " ungeprüfte Frist(en) warten auf Bestätigung");
        }
        return AgentResult.done(parts.isEmpty() ? "Fristen geprüft" : String.join("; ", parts),
                produced, meta);
    }

    private void remind(UUID tenantId, DeadlineRow deadline, Notification.Category category,
                        Notification.Severity severity, String type, String title, String body,
                        String dedupKey) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("taskTitle", deadline.title());
        parameters.put("dueDate", String.valueOf(deadline.dueDate()));
        if (deadline.assignees().isEmpty()) {
            notifier.tell(tenantId, category, severity, type, title, body, "tasks",
                    deadline.taskId(), "/tasks", dedupKey, parameters);
        }
        else {
            notifier.tell(tenantId, deadline.assignees(), category, severity, type, title, body,
                    "tasks", deadline.taskId(), "/tasks", dedupKey, parameters);
        }
    }

    // ------------------------------------------------------------------ reads

    private record DeadlineRow(UUID deadlineId, UUID taskId, String title, LocalDate dueDate,
                               List<UUID> assignees) {
    }

    private List<DeadlineRow> loadDueSoon(UUID tenantId, int leadDays) {
        return jdbc.query("""
                select d.id as deadline_id, t.id as task_id, t.title, d.due_date,
                       coalesce((select array_agg(a.user_id) from task_assignees a
                                  where a.task_id = t.id and a.completed_at is null), '{}') as assignees
                  from task_deadlines d
                  join tasks t on t.id = d.task_id
                 where d.is_active and d.tenant_id = ?
                   and t.deleted_at is null and t.status not in ('DONE', 'CANCELLED')
                   and coalesce(d.due_date, (d.due_at at time zone 'Europe/Berlin')::date)
                       between current_date and current_date + make_interval(days => ?)
                   and not exists (select 1 from notifications n
                                    where n.dedup_key = 'deadline-reminder-' || d.id)
                 order by d.due_date
                """, (rs, row) -> new DeadlineRow(rs.getObject("deadline_id", UUID.class),
                rs.getObject("task_id", UUID.class), rs.getString("title"),
                rs.getDate("due_date") == null ? null : rs.getDate("due_date").toLocalDate(),
                toUuidList(rs.getArray("assignees"))),
                tenantId, Math.max(0, leadDays));
    }

    private List<DeadlineRow> loadOverdue(UUID tenantId) {
        return jdbc.query("""
                select d.id as deadline_id, t.id as task_id, t.title,
                       coalesce(d.due_date, (d.due_at at time zone 'Europe/Berlin')::date) as due_date,
                       coalesce((select array_agg(a.user_id) from task_assignees a
                                  where a.task_id = t.id and a.completed_at is null), '{}') as assignees
                  from task_deadlines d
                  join tasks t on t.id = d.task_id
                 where d.is_active and d.tenant_id = ?
                   and t.deleted_at is null and t.status not in ('DONE', 'CANCELLED')
                   and (coalesce(d.due_date, (d.due_at at time zone 'Europe/Berlin')::date) < current_date
                        or (d.due_date is null and d.due_at < now()))
                   and not exists (select 1 from notifications n
                                    where n.dedup_key = 'deadline-overdue-' || d.id)
                 order by due_date
                """, (rs, row) -> new DeadlineRow(rs.getObject("deadline_id", UUID.class),
                rs.getObject("task_id", UUID.class), rs.getString("title"),
                rs.getDate("due_date") == null ? null : rs.getDate("due_date").toLocalDate(),
                toUuidList(rs.getArray("assignees"))),
                tenantId);
    }

    private long countUnverified(UUID tenantId) {
        Long count = jdbc.queryForObject("""
                select count(*) from task_deadlines d
                join tasks t on t.id = d.task_id
                where d.is_active and d.tenant_id = ? and d.requires_verification
                  and t.deleted_at is null and t.status not in ('DONE', 'CANCELLED')
                """, Long.class, tenantId);
        return count == null ? 0 : count;
    }

    private static List<UUID> toUuidList(java.sql.Array array) {
        if (array == null) {
            return List.of();
        }
        try {
            UUID[] ids = (UUID[]) array.getArray();
            return ids == null ? List.of() : Arrays.asList(ids);
        }
        catch (java.sql.SQLException e) {
            return List.of();
        }
    }

    private int parseLeadDays(String raw) {
        int configured = settings.getInt("deadline_watch.lead_days", 7);
        if (raw == null || raw.isBlank()) {
            return configured;
        }
        try {
            return Math.max(0, Integer.parseInt(raw.strip()));
        }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException("leadDays '" + raw + "' ist keine Zahl");
        }
    }

    private boolean parseIncludeVerification(String raw) {
        if (raw == null || raw.isBlank()) {
            return true;
        }
        return Boolean.parseBoolean(raw.strip());
    }
}
