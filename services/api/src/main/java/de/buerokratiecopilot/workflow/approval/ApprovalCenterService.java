package de.buerokratiecopilot.workflow.approval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.shared.util.JsonColumns;

/**
 * What a person needs to decide an approval (task CA-07): the action, the case it belongs to, the material, the recipient, the
 * grounds, the warnings and the term - gathered in one place, so the decision is made on facts and not on a title.
 *
 * <p>Only what the reader may see is shown: a request of a private case is shown to those who may open the case.</p>
 */
@Service
public class ApprovalCenterService {

    private final JdbcTemplate jdbc;
    private final ApprovalService approvals;

    public ApprovalCenterService(JdbcTemplate jdbc, ApprovalService approvals) {
        this.jdbc = jdbc;
        this.approvals = approvals;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> pending(UUID tenantId, UUID userId, boolean seesEveryCase) {
        List<Map<String, Object>> rows = JsonColumns.parse(jdbc.queryForList("""
                select a.id, a.title, a.justification, a.subject_type, a.subject_id, a.approval_type, a.status, a.required_role,
                       a.required_permission, a.requested_by, u.display_name as requested_by_name, a.expires_at, a.created_at,
                       a.automation_level, a.metadata, a.content_hash is not null as bound
                from approvals a left join users u on u.id = a.requested_by
                where a.tenant_id = ? and a.status = 'PENDING' order by a.created_at
                """, tenantId));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> material = new LinkedHashMap<>();
            List<String> warnings = new ArrayList<>();
            UUID caseId = null;
            String type = String.valueOf(row.get("subject_type"));
            UUID subjectId = (UUID) row.get("subject_id");
            if ("OUTBOUND_MESSAGE".equals(type)) {
                List<Map<String, Object>> m = jdbc.queryForList("""
                        select coalesce(final_subject, subject) as subject, left(coalesce(final_body_text, body_text), 600) as excerpt,
                               to_addresses::text as to_addresses, jsonb_array_length(attachment_document_ids) as attachments, case_id, channel
                        from outbound_messages where id = ? and tenant_id = ?
                        """, subjectId, tenantId);
                if (!m.isEmpty()) {
                    material.putAll(m.get(0));
                    caseId = (UUID) m.get(0).get("case_id");
                    material.remove("case_id");
                }
            }
            if (caseId != null) {
                List<Map<String, Object>> c = jdbc.queryForList("""
                        select c.id, c.reference, c.title from cases c where c.id = ? and c.tenant_id = ? and c.deleted_at is null
                          and (? or c.owner_user_id = ? or c.responsible_user_id = ?
                               or exists (select 1 from case_participants p where p.case_id = c.id and p.user_id = ? and p.revoked_at is null
                                          and (p.expires_at is null or p.expires_at > now())))
                        """, caseId, tenantId, seesEveryCase, userId, userId, userId);
                if (c.isEmpty()) {
                    continue; // a request of a case the reader may not see does not exist for them
                }
                row.put("case", c.get(0));
                Integer findings = jdbc.queryForObject("""
                        select count(*) from radar_findings where tenant_id = ? and related_case_id = ? and status not in ('RESOLVED', 'DISMISSED')
                          and severity in ('HIGH', 'CRITICAL')
                        """, Integer.class, tenantId, caseId);
                if (findings != null && findings > 0) {
                    warnings.add("OPEN_RADAR_FINDINGS");
                }
            }
            row.put("material", material);
            row.put("warnings", warnings);
            row.put("mayDecide", approvals.mayDecide(tenantId, (UUID) row.get("id"), userId));
            result.add(row);
        }
        return result;
    }
}
