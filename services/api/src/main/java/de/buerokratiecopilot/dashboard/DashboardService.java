package de.buerokratiecopilot.dashboard;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The figures behind the dashboard (spec §5.3): every one of them is a question a person can act on, and
 * every one answers to the caller's rights.
 *
 * <p><strong>A figure the caller may not see is absent, not zero.</strong> A zero says "nothing to do";
 * leaving the key out says "not for you". The screen reads a missing key as nothing to show, but a client
 * that listed the keys could otherwise learn that finance exists in an account it has no access to, and a
 * zero would invite the wrong conclusion.</p>
 *
 * <p><strong>Task and case figures follow the same visibility as their lists.</strong> The dashboard cannot
 * be the one place that reveals the existence of work the person is not part of.</p>
 */
@Service
public class DashboardService {

    private static final int DEADLINE_HORIZON_DAYS = 14;
    private static final int RENEWAL_HORIZON_DAYS = 90;
    private static final int ACTIVITY_LIMIT = 10;

    private final JdbcTemplate jdbc;

    public DashboardService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Who is asking and what they may see. */
    public record Viewer(UUID tenantId, UUID userId, Predicate<String> may, boolean seesAllTasks) {
    }

    @Transactional(readOnly = true)
    public Map<String, Object> summary(Viewer viewer) {
        UUID tenant = viewer.tenantId();
        Map<String, Object> counts = new LinkedHashMap<>();

        if (viewer.may().test("documents.view") || viewer.may().test("documents.review")) {
            // "Needs a person": waiting for review, flagged as a possible duplicate, or failed.
            counts.put("pendingDocuments", count("""
                    select count(*) from documents where tenant_id = ? and deleted_at is null
                      and processing_status in ('REVIEW_REQUIRED', 'DUPLICATE', 'FAILED')
                    """, tenant));
        }
        if (viewer.may().test("tasks.view")) {
            String visible = """
                    and (?::boolean or t.created_by_user_id = ? or t.approver_user_id = ?
                         or exists (select 1 from task_assignees a where a.task_id = t.id and a.user_id = ?))
                    """;
            Object[] who = {viewer.seesAllTasks(), viewer.userId(), viewer.userId(), viewer.userId()};
            counts.put("todayTasks", count("""
                    select count(*) from tasks t where t.tenant_id = ? and t.deleted_at is null
                      and t.status not in ('DONE', 'CANCELLED')
                      and (t.due_at at time zone 'Europe/Berlin')::date = (now() at time zone 'Europe/Berlin')::date
                    """ + visible, concat(new Object[] {tenant}, who)));
            counts.put("overdueTasks", count("""
                    select count(*) from tasks t where t.tenant_id = ? and t.deleted_at is null
                      and t.status not in ('DONE', 'CANCELLED') and t.due_at < now()
                    """ + visible, concat(new Object[] {tenant}, who)));
            counts.put("upcomingDeadlines", count("""
                    select count(*) from task_deadlines d join tasks t on t.id = d.task_id and t.deleted_at is null
                    where d.tenant_id = ? and d.is_active and t.status not in ('DONE', 'CANCELLED')
                      and coalesce(d.due_date, (d.due_at at time zone 'Europe/Berlin')::date)
                          between (now() at time zone 'Europe/Berlin')::date
                              and (now() at time zone 'Europe/Berlin')::date + ?
                    """ + visible, concat(new Object[] {tenant, DEADLINE_HORIZON_DAYS}, who)));
        }
        if (viewer.may().test("cases.view")) {
            counts.put("casesMissingDocuments", count("""
                    select count(*) from cases where tenant_id = ? and deleted_at is null
                      and status = 'WAITING_FOR_DOCUMENTS'
                    """, tenant));
        }
        if (viewer.may().test("finance.view")) {
            counts.put("invoicesToReview", count("""
                    select count(*) from invoices where tenant_id = ? and deleted_at is null
                      and status in ('RECEIVED', 'IN_REVIEW')
                    """, tenant));
        }
        if (viewer.may().test("contracts.view")) {
            counts.put("contractRenewals", count("""
                    select count(*) from contracts where tenant_id = ? and deleted_at is null
                      and status in ('ACTIVE', 'EXPIRING')
                      and end_date between current_date and current_date + ?
                    """, tenant, RENEWAL_HORIZON_DAYS));
        }
        if (viewer.may().test("tasks.approve") || viewer.may().test("finance.approve")
                || viewer.may().test("documents.approve")) {
            counts.put("pendingApprovals", count("""
                    select count(*) from approvals where tenant_id = ? and status = 'PENDING'
                    """, tenant));
        }
        // Notifications are the caller's own, so no permission is involved.
        counts.put("criticalNotifications", count("""
                select count(*) from notifications where tenant_id = ? and user_id = ? and not is_read
                  and severity in ('WARNING', 'CRITICAL')
                """, tenant, viewer.userId()));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("generatedAt", Instant.now().toString());
        summary.put("counts", counts);
        summary.put("activity", viewer.may().test("audit.view") ? activity(tenant) : List.of());
        summary.put("automationStatus", viewer.may().test("workflows.view") ? automationStatus(tenant) : List.of());
        summary.put("integrationStatus",
                viewer.may().test("integrations.view") ? integrationStatus(tenant) : List.of());
        return summary;
    }

    private List<Map<String, Object>> activity(UUID tenant) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id::text as id, created_at as at, coalesce(actor_label, actor_type, '') as actor,
                       coalesce(description, action) as summary, entity_label as target
                from audit_events where tenant_id = ?
                  and category not in ('AUTHENTICATION', 'AUTHORIZATION')
                order by created_at desc limit ?
                """, tenant, ACTIVITY_LIMIT);
        return new ArrayList<>(rows);
    }

    private List<Map<String, Object>> automationStatus(UUID tenant) {
        return jdbc.queryForList("""
                select case when coalesce(d.current_version, 0) = 0 then 'draft'
                            when d.is_active then 'active'
                            else 'paused' end as status, count(*) as count
                from workflow_definitions d where d.tenant_id = ? group by 1 order by 1
                """, tenant);
    }

    private List<Map<String, Object>> integrationStatus(UUID tenant) {
        return jdbc.queryForList("""
                select case status when 'CONNECTED' then 'connected'
                                   when 'PENDING_AUTHORIZATION' then 'pending'
                                   when 'ERROR' then 'error' when 'DEGRADED' then 'error'
                                   else 'disconnected' end as status, count(*) as count
                from integrations where tenant_id = ? and deleted_at is null group by 1 order by 1
                """, tenant);
    }

    private long count(String sql, Object... arguments) {
        Long value = jdbc.queryForObject(sql, Long.class, arguments);
        return value == null ? 0 : value;
    }

    private static Object[] concat(Object[] first, Object[] second) {
        Object[] all = java.util.Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
    }
}
