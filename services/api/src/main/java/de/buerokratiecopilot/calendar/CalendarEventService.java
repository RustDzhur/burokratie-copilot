package de.buerokratiecopilot.calendar;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.audit.AuditService;
import de.buerokratiecopilot.audit.domain.AuditEvent;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * What the calendar screen shows: the deadlines of work the caller takes part in, and the appointments and
 * reminders people enter by hand.
 *
 * <p><strong>Deadlines are read, not copied.</strong> They live on tasks and cases; a calendar that stored its
 * own copy would show a date that no longer matched the task the moment somebody moved it. The same
 * visibility rule as the task and case lists applies, so the calendar cannot reveal a deadline of work the
 * person may not open.</p>
 */
@Service
public class CalendarEventService {

    public static final int MAX_RANGE_DAYS = 400;

    public enum Kind { APPOINTMENT, REMINDER }

    public record Draft(String title, LocalDate date, LocalDate endDate, Kind kind, UUID caseId) {
    }

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public CalendarEventService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /**
     * Events from {@code from} to {@code to} inclusive, in date order.
     *
     * @param seeAll true for a caller who may see all tasks and cases of the account (assignment right)
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> events(UUID tenantId, UUID userId, boolean seeAll, LocalDate from,
                                            LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Der Zeitraum ist ungültig");
        }
        if (from.plusDays(MAX_RANGE_DAYS).isBefore(to)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Der Zeitraum darf höchstens " + MAX_RANGE_DAYS + " Tage umfassen");
        }

        List<Map<String, Object>> events = new ArrayList<>();

        events.addAll(jdbc.queryForList("""
                select e.id::text as id, e.title, e.starts_on as date, e.ends_on as end_date,
                       e.event_type as type, e.all_day, c.reference as case_reference
                from calendar_events e left join cases c on c.id = e.case_id
                where e.tenant_id = ? and e.deleted_at is null
                  and e.starts_on <= ? and coalesce(e.ends_on, e.starts_on) >= ?
                """, tenantId, to, from));

        events.addAll(jdbc.queryForList("""
                select 'task-deadline:' || d.id as id, t.title,
                       coalesce(d.due_date, (d.due_at at time zone 'Europe/Berlin')::date) as date,
                       null::date as end_date, 'DEADLINE' as type, true as all_day,
                       c.reference as case_reference
                from task_deadlines d
                join tasks t on t.id = d.task_id and t.deleted_at is null
                left join cases c on c.id = coalesce(d.case_id, t.case_id)
                where d.tenant_id = ? and d.is_active
                  and coalesce(d.due_date, (d.due_at at time zone 'Europe/Berlin')::date) between ? and ?
                  and (?::boolean or t.created_by_user_id = ? or t.approver_user_id = ?
                       or exists (select 1 from task_assignees a where a.task_id = t.id and a.user_id = ?))
                """, tenantId, from, to, seeAll, userId, userId, userId));

        events.addAll(jdbc.queryForList("""
                select 'case-due:' || c.id as id, c.title,
                       (c.due_at at time zone 'Europe/Berlin')::date as date,
                       null::date as end_date, 'DEADLINE' as type, true as all_day,
                       c.reference as case_reference
                from cases c
                where c.tenant_id = ? and c.deleted_at is null and c.due_at is not null
                  and (c.due_at at time zone 'Europe/Berlin')::date between ? and ?
                  and (?::boolean or c.owner_user_id = ? or c.responsible_user_id = ?
                       or exists (select 1 from case_participants p
                                   where p.case_id = c.id and p.user_id = ? and p.revoked_at is null
                                     and (p.expires_at is null or p.expires_at > now())))
                """, tenantId, from, to, seeAll, userId, userId, userId));

        events.sort(Comparator.comparing((Map<String, Object> event) -> String.valueOf(event.get("date")))
                .thenComparing(event -> String.valueOf(event.get("title"))));
        return events;
    }

    @Transactional
    public Map<String, Object> create(UUID tenantId, Draft draft, UUID createdBy) {
        if (draft.title() == null || draft.title().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Ein Termin braucht einen Titel");
        }
        if (draft.date() == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Ein Termin braucht ein Datum");
        }
        if (draft.endDate() != null && draft.endDate().isBefore(draft.date())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Das Ende liegt vor dem Beginn");
        }
        Kind kind = draft.kind() == null ? Kind.APPOINTMENT : draft.kind();

        UUID id = jdbc.queryForObject("""
                insert into calendar_events (id, tenant_id, title, event_type, starts_on, ends_on, case_id,
                                             created_by)
                values (gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?)
                returning id
                """, UUID.class, tenantId, draft.title().strip(), kind.name(), draft.date(), draft.endDate(),
                draft.caseId(), createdBy);

        audit.record(AuditService.AuditEntry.builder(AuditEvent.Category.CASE, "CALENDAR_EVENT_CREATED")
                .tenant(tenantId)
                .entity("calendar_events", id, draft.title().strip())
                .description("Calendar entry created")
                .build());

        return jdbc.queryForMap("""
                select e.id::text as id, e.title, e.starts_on as date, e.ends_on as end_date,
                       e.event_type as type, e.all_day, null as case_reference
                from calendar_events e where e.id = ? and e.tenant_id = ?
                """, id, tenantId);
    }
}
