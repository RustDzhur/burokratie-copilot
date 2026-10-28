package de.buerokratiecopilot.orchestration;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.tasks.TaskService;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * Keeps a matter open after the action was taken, until the other side has confirmed it.
 *
 * <p>The specification is explicit that the platform must not treat a matter as finished because it read a
 * document or created a reminder: a reply that was sent is not a reply that was received, and a payment that
 * was released is not a payment that arrived. For a scenario that declares a follow-up
 * ({@code follow_up_days}), completing the task creates a successor task due after that many days whose
 * completion criterion is the confirmation itself. If nothing confirms, that task goes overdue and the
 * watchdog tells the person responsible; it is never closed on the platform's own initiative.</p>
 */
@Service
public class FollowUpService {

    private static final Logger log = LoggerFactory.getLogger(FollowUpService.class);
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private final JdbcTemplate jdbc;
    private final TaskService tasks;

    public FollowUpService(JdbcTemplate jdbc, TaskService tasks) {
        this.jdbc = jdbc;
        this.tasks = tasks;
    }

    /** Creates the follow-up task of a completed document task, or returns null when none is due. */
    @Transactional
    public UUID onTaskCompleted(UUID taskId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select t.tenant_id, t.case_id, t.source_document_id, t.completed_by, t.title, t.priority,
                       s.follow_up_days, s.follow_up_title, dt.code as type_code
                from tasks t
                join documents d on d.id = t.source_document_id
                join document_types dt on dt.id = d.document_type_id
                join document_type_scenarios s on s.document_type_code = dt.code and s.tenant_id is null
                where t.id = ? and t.source = 'DOCUMENT' and t.parent_task_id is null
                  and s.follow_up_days is not null
                """, taskId);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        if (!jdbc.queryForList("select id from tasks where parent_task_id = ? and deleted_at is null", taskId)
                .isEmpty()) {
            return null;
        }
        UUID tenantId = (UUID) row.get("tenant_id");
        UUID completedBy = (UUID) row.get("completed_by");
        if (completedBy == null) {
            log.info("Task {} was completed without a person recorded, no follow-up is created", taskId);
            return null;
        }
        int days = ((Number) row.get("follow_up_days")).intValue();
        String title = String.valueOf(row.get("follow_up_title"));
        Task follow = tasks.create(new TaskService.CreateRequest(tenantId, (UUID) row.get("case_id"), taskId,
                title,
                "Die Aufgabe „" + row.get("title") + "“ ist abgeschlossen. Die Angelegenheit ist erst "
                        + "erledigt, wenn die Gegenseite den Eingang oder das Ergebnis bestätigt hat, oder wenn Sie "
                        + "ausdrücklich entscheiden, dass nichts weiter nötig ist.",
                Task.Priority.NORMAL, "RULE", "Nachverfolgung nach Szenario " + row.get("type_code"),
                LocalDate.now(BERLIN).plusDays(days).atTime(9, 0).atZone(BERLIN).toInstant(), null,
                "Die Bestätigung der Gegenseite liegt vor, oder der Verantwortliche hat ausdrücklich "
                        + "entschieden, dass keine weitere Maßnahme nötig ist.",
                false, null, completedBy, List.of(String.valueOf(row.get("type_code")), "follow-up"),
                Task.Source.SYSTEM, (UUID) row.get("source_document_id")), completedBy);
        tasks.addChecklistItem(follow.getId(), "Bestätigung der Gegenseite prüfen", true);
        tasks.addChecklistItem(follow.getId(), "Falls keine Bestätigung vorliegt: nachfassen", false);
        log.info("Follow-up task {} created after task {} ({} days)", follow.getId(), taskId, days);
        return follow.getId();
    }
}
