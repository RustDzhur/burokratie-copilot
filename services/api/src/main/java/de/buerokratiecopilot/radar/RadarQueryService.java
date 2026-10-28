package de.buerokratiecopilot.radar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.util.JsonColumns;

/**
 * Reads findings for people. A finding that belongs to a private case is visible only to those who may see that case.
 */
@Service
public class RadarQueryService {

    /** Filters of the list; every one is optional. */
    public record Filter(List<String> status, List<String> severity, List<String> type, UUID caseId, UUID contactId,
                         UUID assignedTo, Boolean open, UUID documentId) {
        public Filter(List<String> status, List<String> severity, List<String> type, UUID caseId, UUID contactId,
                      UUID assignedTo, Boolean open) {
            this(status, severity, type, caseId, contactId, assignedTo, open, null);
        }
    }

    private final JdbcTemplate jdbc;

    public RadarQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static boolean seesEveryCase() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet()).contains("cases.assign");
    }

    /** SQL that keeps out findings of cases the reader may not see; needs the user id twice more as parameters. */
    private static final String VISIBLE = """
             and (f.related_case_id is null or ? or exists (select 1 from cases vc where vc.id = f.related_case_id
                  and (vc.owner_user_id = ? or vc.responsible_user_id = ?
                       or exists (select 1 from case_participants p where p.case_id = vc.id and p.user_id = ?
                                  and p.revoked_at is null and (p.expires_at is null or p.expires_at > now())))))
            """;

    @Transactional(readOnly = true)
    public Map<String, Object> list(UUID tenantId, UUID userId, Filter filter, int page, int size) {
        int limit = Math.max(1, Math.min(size, 100));
        int offset = Math.max(0, page) * limit;
        StringBuilder where = new StringBuilder(" where f.tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        where.append(VISIBLE);
        args.add(seesEveryCase());
        args.add(userId);
        args.add(userId);
        args.add(userId);
        in(where, args, "f.status", filter.status());
        in(where, args, "f.severity", filter.severity());
        in(where, args, "f.finding_type", filter.type());
        if (filter.caseId() != null) {
            where.append(" and f.related_case_id = ?");
            args.add(filter.caseId());
        }
        if (filter.contactId() != null) {
            where.append(" and f.related_contact_id = ?");
            args.add(filter.contactId());
        }
        if (filter.documentId() != null) {
            where.append(" and exists (select 1 from radar_finding_evidence de where de.finding_id = f.id and de.document_id = ?)");
            args.add(filter.documentId());
        }
        if (filter.assignedTo() != null) {
            where.append(" and f.assigned_to = ?");
            args.add(filter.assignedTo());
        }
        if (Boolean.TRUE.equals(filter.open())) {
            where.append(" and f.status not in ('RESOLVED', 'DISMISSED')");
        }
        Long total = jdbc.queryForObject("select count(*) from radar_findings f" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(limit);
        pageArgs.add(offset);
        List<Map<String, Object>> items = jdbc.queryForList("""
                select f.id, f.finding_key, f.finding_type, f.rule_id, f.rule_version, f.title, f.description, f.status,
                       f.severity, f.confidence, f.detected_at, f.last_evaluated_at, f.related_case_id, f.related_contact_id,
                       f.related_contract_id, f.reason_codes, f.recommended_actions, f.assigned_to, f.snoozed_until,
                       f.resolved_at, f.resolution_reason, f.explanation, f.reopen_count,
                       (select count(*) from radar_finding_evidence e where e.finding_id = f.id) as evidence_count
                from radar_findings f""" + where + """
                 order by case f.severity when 'CRITICAL' then 0 when 'HIGH' then 1 when 'MEDIUM' then 2 when 'LOW' then 3 else 4 end,
                          f.detected_at desc
                 limit ? offset ?
                """, pageArgs.toArray());
        JsonColumns.parse(items);
        for (Map<String, Object> item : items) {
            item.put("allowedStatuses", allowed((String) item.get("status")));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("page", page);
        result.put("size", limit);
        result.put("total", total == null ? 0 : total);
        return result;
    }

    /** Counts by status and severity of what the reader may see: the numbers for the dashboard block. */
    @Transactional(readOnly = true)
    public Map<String, Object> summary(UUID tenantId, UUID userId) {
        List<Object> args = new ArrayList<>(List.of(tenantId, seesEveryCase(), userId, userId, userId));
        List<Map<String, Object>> byStatus = jdbc.queryForList(
                "select f.status, count(*) as count from radar_findings f where f.tenant_id = ? " + VISIBLE + " group by f.status",
                args.toArray());
        List<Map<String, Object>> openBySeverity = jdbc.queryForList(
                "select f.severity, count(*) as count from radar_findings f where f.tenant_id = ? " + VISIBLE
                        + " and f.status not in ('RESOLVED', 'DISMISSED') group by f.severity", args.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> status = new LinkedHashMap<>();
        for (Map<String, Object> row : byStatus) {
            status.put((String) row.get("status"), row.get("count"));
        }
        Map<String, Object> severity = new LinkedHashMap<>();
        for (Map<String, Object> row : openBySeverity) {
            severity.put((String) row.get("severity"), row.get("count"));
        }
        result.put("byStatus", status);
        result.put("openBySeverity", severity);
        result.put("recentlyResolved", jdbc.queryForList("""
                select f.id, f.title, f.severity, f.resolved_at from radar_findings f
                where f.tenant_id = ? """ + VISIBLE + """
                  and f.status in ('RESOLVED', 'DISMISSED') and f.resolved_at > now() - interval '14 days'
                order by f.resolved_at desc limit 5
                """, args.toArray()));
        return result;
    }

    /** One finding with its evidence and history; unknown or not visible is "not found". */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(UUID tenantId, UUID userId, UUID findingId) {
        return detailIfVisible(tenantId, userId, findingId).orElseThrow(() -> ApiException.notFound("Befund"));
    }

    /** Like {@link #detail}, but empty instead of throwing: a caller inside a transaction must not have it marked rollback-only. */
    @Transactional(readOnly = true)
    public java.util.Optional<Map<String, Object>> detailIfVisible(UUID tenantId, UUID userId, UUID findingId) {
        List<Object> args = new ArrayList<>(List.of(tenantId, findingId, seesEveryCase(), userId, userId, userId));
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select f.* from radar_findings f where f.tenant_id = ? and f.id = ?""" + VISIBLE, args.toArray());
        if (rows.isEmpty()) {
            return java.util.Optional.empty();
        }
        Map<String, Object> finding = JsonColumns.parse(new LinkedHashMap<>(rows.get(0)));
        finding.remove("material_hash");
        finding.put("allowedStatuses", allowed((String) finding.get("status")));
        finding.put("evidence", jdbc.queryForList("""
                select e.id, e.role, e.document_id, d.original_filename as document_name, e.page, e.snippet, e.fact_id,
                       e.contract_clause_id
                from radar_finding_evidence e left join documents d on d.id = e.document_id
                where e.finding_id = ? order by e.role, e.created_at
                """, findingId));
        finding.put("history", JsonColumns.parse(jdbc.queryForList("""
                select id, event_type, from_status, to_status, actor_user_id, reason, details, created_at
                from radar_finding_events where finding_id = ? order by created_at desc, id desc
                """, findingId)));
        return java.util.Optional.of(finding);
    }

    /** Throws "not found" unless the reader may see the finding. */
    @Transactional(readOnly = true)
    public Map<String, Object> requireVisible(UUID tenantId, UUID userId, UUID findingId) {
        List<Object> args = new ArrayList<>(List.of(tenantId, findingId, seesEveryCase(), userId, userId, userId));
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select f.id, f.status, f.related_case_id, f.related_contact_id, f.title, f.description, f.severity, f.rule_id "
                        + "from radar_findings f where f.tenant_id = ? and f.id = ?" + VISIBLE, args.toArray());
        if (rows.isEmpty()) {
            throw ApiException.notFound("Befund");
        }
        return rows.get(0);
    }

    static Set<String> allowed(String status) {
        return RadarFindingStatus.valueOf(status).next().stream().map(Enum::name).collect(Collectors.toCollection(java.util.TreeSet::new));
    }

    private static void in(StringBuilder where, List<Object> args, String column, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        where.append(" and ").append(column).append(" in (")
                .append(String.join(",", values.stream().map(v -> "?").toList())).append(")");
        args.addAll(values.stream().map(v -> v.toUpperCase(java.util.Locale.ROOT)).toList());
    }
}
