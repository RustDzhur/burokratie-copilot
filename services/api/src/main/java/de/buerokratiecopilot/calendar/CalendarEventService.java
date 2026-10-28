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
import de.buerokratiecopilot.calendar.caldav.CalDavSyncService;
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
    private final CalDavSyncService calDavSync;

    public CalendarEventService(JdbcTemplate jdbc, AuditService audit, CalDavSyncService calDavSync) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.calDavSync = calDavSync;
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
                       e.event_type as type, e.all_day, c.reference as case_reference,
                       e.case_id::text as case_id, e.note, e.source_document_id::text as document_id,
                       'CALENDAR_ENTRY' as origin
                from calendar_events e left join cases c on c.id = e.case_id
                where e.tenant_id = ? and e.deleted_at is null
                  and e.starts_on <= ? and coalesce(e.ends_on, e.starts_on) >= ?
                  -- The same visibility the task deadlines and the case due dates already have. This read
                  -- had none, so every personal entry and every entry derived from a document was shown to
                  -- everybody in the account and landed in their calendar feed. An entry with no owner and
                  -- no case stays visible: it predates the rule, and hiding it would lose a date silently.
                  and (?::boolean
                       or e.created_by = ?
                       or (e.case_id is not null and exists (
                             select 1 from case_participants p
                              where p.case_id = e.case_id and p.user_id = ? and p.revoked_at is null
                                and (p.expires_at is null or p.expires_at > now())))
                       or (e.created_by is null and e.case_id is null))
                """, tenantId, to, from, seeAll, userId, userId));

        events.addAll(jdbc.queryForList("""
                select 'task-deadline:' || d.id as id,
                       case d.deadline_type
                            when 'DOCUMENT_DATE' then 'Frist laut Dokument: '
                            when 'ASSUMED_LEGAL' then 'Angenommene Frist (ungeprüft): '
                            when 'INTERNAL' then 'Interner Termin: '
                            when 'REMINDER' then 'Erinnerung: '
                            else '' end || t.title as title,
                       coalesce(d.due_date, (d.due_at at time zone 'Europe/Berlin')::date) as date,
                       null::date as end_date, 'DEADLINE' as type, true as all_day,
                       c.reference as case_reference, coalesce(d.case_id, t.case_id)::text as case_id,
                       concat_ws(E'\n\n', d.note,
                                 case when d.source_snippet is not null
                                      then 'Quelle im Dokument: „' || d.source_snippet || '“' end) as note,
                       d.source_document_id::text as document_id, 'TASK_DEADLINE' as origin,
                       t.id::text as task_id
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
                       c.reference as case_reference, c.id::text as case_id,
                       'Fälligkeit des Vorgangs ' || c.reference as note, null::text as document_id,
                       'CASE_DUE' as origin
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
        return create(tenantId, draft, createdBy, null, null);
    }

    /** An entry the platform creates from a document: it keeps the sentence that explains it and the document. */
    @Transactional
    public Map<String, Object> create(UUID tenantId, Draft draft, UUID createdBy, String note,
                                      UUID sourceDocumentId) {
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
                                             created_by, note, source_document_id)
                values (gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, ?, ?)
                returning id
                """, UUID.class, tenantId, draft.title().strip(), kind.name(), draft.date(), draft.endDate(),
                draft.caseId(), createdBy, note, sourceDocumentId);

        audit.record(AuditService.AuditEntry.builder(AuditEvent.Category.CASE, "CALENDAR_EVENT_CREATED")
                .tenant(tenantId)
                .entity("calendar_events", id, draft.title().strip())
                .description("Calendar entry created")
                .build());

        Map<String, Object> created = jdbc.queryForMap("""
                select e.id::text as id, e.title, e.starts_on as date, e.ends_on as end_date,
                       e.event_type as type, e.all_day, null as case_reference, e.case_id::text as case_id,
                       e.note, e.source_document_id::text as document_id, 'CALENDAR_ENTRY' as origin
                from calendar_events e where e.id = ? and e.tenant_id = ?
                """, id, tenantId);

        // Best effort: an entry stored here also goes into a connected external calendar; a failing
        // push is recorded on the connection and never disturbs the local entry.
        calDavSync.afterCreated(tenantId, createdBy, created);
        return created;
    }
}
