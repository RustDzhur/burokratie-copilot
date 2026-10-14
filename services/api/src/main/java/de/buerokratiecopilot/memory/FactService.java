package de.buerokratiecopilot.memory;

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
 * Facts of the business memory: the values read from documents, with their origin, their verification and their history.
 *
 * <p>A fact is a row of {@code document_extractions} (decision D-04). The history is kept by the database
 * ({@code fact_revisions}); this service reads it and attaches a fact to the contact, contract, case or invoice it is about.</p>
 */
@Service
public class FactService {

    private static final Map<String, String> SUBJECT_TABLES = Map.of(
            "CONTACT", "contacts", "CONTRACT", "contracts", "CASE", "cases", "INVOICE", "invoices");

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public FactService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** The facts read from a document, each with its verification and the number of changes it went through. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> factsOfDocument(UUID tenantId, UUID documentId) {
        return jdbc.queryForList("""
                select e.id, e.field_key, e.field_value, e.normalized_value, e.value_type, e.confidence, e.extractor,
                       e.source_kind, e.source_page, e.source_snippet, e.verification, e.verified_by, e.verified_at,
                       e.subject_type, e.subject_id,
                       (select count(*) from fact_revisions r where r.extraction_id = e.id) as revision_count
                from document_extractions e join documents d on d.id = e.document_id
                where d.tenant_id = ? and d.id = ? and d.deleted_at is null
                order by e.field_key
                """, tenantId, documentId);
    }

    /** What a fact said before, newest change first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> revisions(UUID tenantId, UUID factId) {
        Integer exists = jdbc.queryForObject("""
                select count(*) from document_extractions e join documents d on d.id = e.document_id
                where e.id = ? and d.tenant_id = ?
                """, Integer.class, factId, tenantId);
        if (exists == null || exists == 0) {
            throw ApiException.notFound("Fakt");
        }
        return jdbc.queryForList("""
                select r.id, r.previous_value, r.new_value, r.previous_verification, r.new_verification, r.changed_by,
                       r.changed_at, r.reason, r.supersedes_revision_id
                from fact_revisions r where r.tenant_id = ? and r.extraction_id = ?
                order by r.changed_at desc, r.id desc
                """, tenantId, factId);
    }

    /** The facts about a contact, contract, case or invoice, over all documents. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> factsOfSubject(UUID tenantId, String subjectType, UUID subjectId) {
        requireKnownType(subjectType);
        return jdbc.queryForList("""
                select e.id, e.document_id, e.field_key, e.field_value, e.verification, e.confidence, e.source_page,
                       e.source_snippet, e.updated_at
                from document_extractions e join documents d on d.id = e.document_id
                where d.tenant_id = ? and d.deleted_at is null and e.subject_type = ? and e.subject_id = ?
                order by e.field_key, e.updated_at desc
                """, tenantId, subjectType, subjectId);
    }

    /** Attaches a fact to the object it is about; the object must belong to the same tenant. */
    @Transactional
    public Map<String, Object> assignSubject(UUID tenantId, UUID factId, String subjectType, UUID subjectId) {
        requireKnownType(subjectType);
        Integer target = jdbc.queryForObject("select count(*) from " + SUBJECT_TABLES.get(subjectType)
                + " where id = ? and tenant_id = ?", Integer.class, subjectId, tenantId);
        if (target == null || target == 0) {
            throw ApiException.notFound("Objekt");
        }
        int changed = jdbc.update("""
                update document_extractions e set subject_type = ?, subject_id = ?
                from documents d where e.id = ? and d.id = e.document_id and d.tenant_id = ?
                """, subjectType, subjectId, factId, tenantId);
        if (changed == 0) {
            throw ApiException.notFound("Fakt");
        }
        audit.record(AuditService.AuditEntry.builder(AuditEvent.Category.DOCUMENT, "FACT_SUBJECT_ASSIGNED")
                .tenant(tenantId).entity("document_extractions", factId, null)
                .description("Fact attached to " + subjectType.toLowerCase(java.util.Locale.ROOT)).build());
        return Map.of("id", factId, "subjectType", subjectType, "subjectId", subjectId);
    }

    private static void requireKnownType(String subjectType) {
        if (subjectType == null || !SUBJECT_TABLES.containsKey(subjectType)) {
            throw ApiException.validation("Unbekannter Objekttyp", List.of());
        }
    }
}
