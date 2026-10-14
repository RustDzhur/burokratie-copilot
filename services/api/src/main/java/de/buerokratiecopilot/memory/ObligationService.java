package de.buerokratiecopilot.memory;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.audit.AuditService;
import de.buerokratiecopilot.audit.domain.AuditEvent;
import de.buerokratiecopilot.shared.error.ApiException;

/**
 * The obligations of contracts as the memory sees them: with their due date, their owner and the tasks that serve them.
 *
 * <p>An obligation is neglected when nobody is responsible and no open task answers it. Radar and Simulator read the same
 * list, so the definition of "neglected" lives here once.</p>
 */
@Service
public class ObligationService {

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public ObligationService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /**
     * Open obligations, soonest first.
     *
     * @param unowned   only those with no responsible person
     * @param withoutTask only those no open task serves
     * @param dueBefore only those due before this day (obligations without a date are kept: they are not "later")
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> open(UUID tenantId, boolean unowned, boolean withoutTask, LocalDate dueBefore) {
        return jdbc.queryForList("""
                select o.id, o.contract_id, k.title as contract_title, o.title, o.obligation_type, o.due_date, o.recurrence,
                       o.amount, o.currency, o.status, o.responsible_user_id,
                       (select count(*) from tasks t where t.obligation_id = o.id and t.deleted_at is null
                          and t.completed_at is null and t.cancelled_at is null) as open_tasks
                from contract_obligations o join contracts k on k.id = o.contract_id
                where o.tenant_id = ? and k.deleted_at is null and o.status in ('OPEN', 'DUE')
                  and (? = false or o.responsible_user_id is null)
                  and (? = false or not exists (select 1 from tasks t where t.obligation_id = o.id and t.deleted_at is null
                                                and t.completed_at is null and t.cancelled_at is null))
                  and (?::date is null or o.due_date is null or o.due_date < ?::date)
                order by o.due_date asc nulls last, o.created_at
                """, tenantId, unowned, withoutTask, dueBefore, dueBefore);
    }

    /** Lets a task answer an obligation. Both must belong to the tenant. */
    @Transactional
    public Map<String, Object> link(UUID tenantId, UUID obligationId, UUID taskId) {
        requireObligation(tenantId, obligationId);
        int changed = jdbc.update("update tasks set obligation_id = ? where id = ? and tenant_id = ? and deleted_at is null",
                obligationId, taskId, tenantId);
        if (changed == 0) {
            throw ApiException.notFound("Aufgabe");
        }
        audit.record(AuditService.AuditEntry.builder(AuditEvent.Category.CONTRACT, "OBLIGATION_TASK_LINKED")
                .tenant(tenantId).entity("contract_obligations", obligationId, null)
                .description("Task linked to an obligation").build());
        return Map.of("obligationId", obligationId, "taskId", taskId);
    }

    @Transactional
    public void unlink(UUID tenantId, UUID obligationId, UUID taskId) {
        requireObligation(tenantId, obligationId);
        int changed = jdbc.update("""
                update tasks set obligation_id = null where id = ? and tenant_id = ? and obligation_id = ?
                """, taskId, tenantId, obligationId);
        if (changed == 0) {
            throw ApiException.notFound("Verknüpfung");
        }
        audit.record(AuditService.AuditEntry.builder(AuditEvent.Category.CONTRACT, "OBLIGATION_TASK_UNLINKED")
                .tenant(tenantId).entity("contract_obligations", obligationId, null)
                .description("Task unlinked from an obligation").build());
    }

    private void requireObligation(UUID tenantId, UUID obligationId) {
        Integer found = jdbc.queryForObject("select count(*) from contract_obligations where id = ? and tenant_id = ?",
                Integer.class, obligationId, tenantId);
        if (found == null || found == 0) {
            throw ApiException.notFound("Pflicht");
        }
    }
}
