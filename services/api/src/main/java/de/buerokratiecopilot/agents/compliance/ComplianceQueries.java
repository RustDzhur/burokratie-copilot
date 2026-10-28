package de.buerokratiecopilot.agents.compliance;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Read-only data access of the compliance agents.
 *
 * <p>The agents decide, this class only fetches. Every statement carries the tenant filter and
 * never writes: a compliance check must not change the situation it reports on, which is also
 * what makes the agents idempotent. The shapes mirror the module services (retention, privacy)
 * so the agents and the modules answer from the same facts.</p>
 */
@Component
public class ComplianceQueries {

    private final JdbcTemplate jdbc;

    public ComplianceQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Core facts of one document, or {@code null} when it does not exist for the tenant. */
    public Map<String, Object> documentFacts(UUID tenantId, UUID documentId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, original_filename, document_date, received_at, deleted_at, archived_at,
                       legal_hold, retention_policy_id, processing_status, review_status
                from documents where id = ? and tenant_id = ?
                """, documentId, tenantId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** Document facts joined with its retention policy, or {@code null} when the document does not exist. */
    public Map<String, Object> documentRetentionFacts(UUID tenantId, UUID documentId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select d.id, d.original_filename, d.document_date, d.received_at, d.deleted_at, d.archived_at,
                       d.legal_hold, d.retention_policy_id,
                       p.code as policy_code, p.retention_period_months, p.retention_trigger,
                       p.legal_basis, p.requires_verification, p.verified_at
                from documents d
                left join document_retention_policies p on p.id = d.retention_policy_id
                where d.id = ? and d.tenant_id = ?
                """, documentId, tenantId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** Core facts of one case, or {@code null} when it does not exist for the tenant. */
    public Map<String, Object> caseFacts(UUID tenantId, UUID caseId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, reference, title, status, closed_at, outcome, counterparty_contact_id
                from cases where id = ? and tenant_id = ? and deleted_at is null
                """, caseId, tenantId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** Retention policies that are in force but not yet confirmed by a person. */
    public List<Map<String, Object>> unverifiedPolicies(UUID tenantId) {
        return jdbc.queryForList("""
                select id, code, retention_period_months, legal_basis
                from document_retention_policies
                where tenant_id = ? and requires_verification and (valid_to is null or valid_to > now())
                order by code
                """, tenantId);
    }

    public boolean contactExists(UUID tenantId, UUID contactId) {
        Integer found = jdbc.queryForObject(
                "select count(*) from contacts where id = ? and tenant_id = ? and deleted_at is null",
                Integer.class, contactId, tenantId);
        return found != null && found > 0;
    }

    /** How much personal data is held about a contact, by kind (same shapes as the privacy module). */
    public Map<String, Object> contactCounts(UUID tenantId, UUID contactId) {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("identifiers", count("select count(*) from contact_identifiers where tenant_id = ? and contact_id = ?",
                tenantId, contactId));
        counts.put("documents", count("""
                select count(*) from document_matches m join documents d on d.id = m.document_id
                where m.tenant_id = ? and m.match_type = 'CONTACT' and m.target_id = ? and m.status <> 'REJECTED'
                  and d.deleted_at is null
                """, tenantId, contactId));
        counts.put("documentsUnderLegalHold", count("""
                select count(*) from document_matches m join documents d on d.id = m.document_id
                where m.tenant_id = ? and m.match_type = 'CONTACT' and m.target_id = ? and m.status <> 'REJECTED'
                  and d.deleted_at is null and d.legal_hold
                """, tenantId, contactId));
        counts.put("contracts", count(
                "select count(*) from contracts where tenant_id = ? and counterparty_contact_id = ? and deleted_at is null",
                tenantId, contactId));
        counts.put("invoices", count(
                "select count(*) from invoices where tenant_id = ? and contact_id = ? and deleted_at is null",
                tenantId, contactId));
        counts.put("cases", count(
                "select count(*) from cases where tenant_id = ? and counterparty_contact_id = ? and deleted_at is null",
                tenantId, contactId));
        counts.put("mailMessages", count("""
                select count(*) from mail_messages m where m.tenant_id = ? and exists (
                    select 1 from contacts c where c.id = ? and c.email is not null
                      and (m.from_address = c.email or m.to_addresses @> to_jsonb(c.email::text)))
                """, tenantId, contactId));
        return counts;
    }

    /** The documents of a contact with their retention facts, for the deletion plan. */
    public List<Map<String, Object>> contactDocumentRetention(UUID tenantId, UUID contactId) {
        return jdbc.queryForList("""
                select d.id, d.original_filename, d.document_date, d.received_at, d.legal_hold,
                       p.code as policy_code, p.retention_period_months, p.requires_verification
                from document_matches m
                join documents d on d.id = m.document_id
                left join document_retention_policies p on p.id = d.retention_policy_id
                where m.tenant_id = ? and m.match_type = 'CONTACT' and m.target_id = ?
                  and m.status <> 'REJECTED' and d.deleted_at is null
                order by d.received_at
                """, tenantId, contactId);
    }

    /** Documents whose retention period ends within the next {@code days} days (expired excluded). */
    public List<Map<String, Object>> upcomingDeletions(UUID tenantId, int days) {
        return jdbc.queryForList("""
                select d.id, d.original_filename, p.code as policy_code,
                       (coalesce(d.document_date, d.received_at::date)
                        + (p.retention_period_months || ' months')::interval)::date as due_date
                from documents d
                join document_retention_policies p on p.id = d.retention_policy_id
                where d.tenant_id = ? and d.deleted_at is null and d.legal_hold = false
                  and p.requires_verification = false and p.retention_period_months is not null
                  and (coalesce(d.document_date, d.received_at::date)
                       + (p.retention_period_months || ' months')::interval)::date
                      between current_date and current_date + ?
                order by due_date
                """, tenantId, days);
    }

    /** Closing date of the most recently closed case linked to the document, or {@code null}. */
    public LocalDate latestCaseClosedDate(UUID tenantId, UUID documentId) {
        List<java.sql.Date> rows = jdbc.queryForList("""
                select max(c.closed_at)::date from case_documents cd
                join cases c on c.id = cd.case_id
                where cd.document_id = ? and cd.tenant_id = ? and c.closed_at is not null and c.deleted_at is null
                """, java.sql.Date.class, documentId, tenantId);
        if (rows.isEmpty() || rows.getFirst() == null) {
            return null;
        }
        return rows.getFirst().toLocalDate();
    }

    /** End date of the contract linked to the document, or {@code null}. */
    public LocalDate latestContractEndDate(UUID tenantId, UUID documentId) {
        List<java.sql.Date> rows = jdbc.queryForList("""
                select max(c.end_date) from document_matches m
                join contracts c on c.id = m.target_id
                where m.document_id = ? and m.tenant_id = ? and m.match_type = 'CONTRACT'
                  and m.status <> 'REJECTED' and c.deleted_at is null
                """, java.sql.Date.class, documentId, tenantId);
        if (rows.isEmpty() || rows.getFirst() == null) {
            return null;
        }
        return rows.getFirst().toLocalDate();
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }
}
