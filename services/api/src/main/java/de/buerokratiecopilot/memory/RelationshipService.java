package de.buerokratiecopilot.memory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.audit.AuditService;
import de.buerokratiecopilot.audit.domain.AuditEvent;
import de.buerokratiecopilot.documents.domain.DocumentMatch;
import de.buerokratiecopilot.documents.matching.MatchingService;
import de.buerokratiecopilot.shared.error.ApiException;

/**
 * The relationships of the business memory, read and decided in one place.
 *
 * <p>Two tables hold them (decision D-02): {@code document_matches} (a document to a case, contact, contract, invoice,
 * obligation or task) and {@code document_relations} (a document to another document). Both carry a status
 * (SUGGESTED, CONFIRMED, REJECTED) and the evidence. A rejected relationship stays recorded, so the same wrong suggestion
 * does not come back.</p>
 */
@Service
public class RelationshipService {

    /** What a relationship list is about. */
    public enum Subject {
        CONTACT, CONTRACT, CASE, DOCUMENT
    }

    private final JdbcTemplate jdbc;
    private final MatchingService matching;
    private final AuditService audit;

    public RelationshipService(JdbcTemplate jdbc, MatchingService matching, AuditService audit) {
        this.jdbc = jdbc;
        this.matching = matching;
        this.audit = audit;
    }

    /** All relationships of a contact, contract, case or document, newest first; {@code status} may filter. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(UUID tenantId, Subject subject, UUID subjectId, String status) {
        String wanted = status == null || status.isBlank() ? "" : status.toUpperCase(java.util.Locale.ROOT);
        List<Map<String, Object>> out = new ArrayList<>();
        if (subject != Subject.DOCUMENT) {
            String matchType = subject.name();
            out.addAll(jdbc.queryForList("""
                    select m.id, 'MATCH' as source, m.match_type as type, m.status, m.score as confidence, m.rationale,
                           m.evidence, m.decided_by, m.decided_at, m.created_at,
                           d.id as document_id, d.original_filename as document_name
                    from document_matches m join documents d on d.id = m.document_id
                    where m.tenant_id = ? and m.match_type = ? and m.target_id = ? and d.deleted_at is null
                      and (? = '' or m.status = ?)
                    order by m.created_at desc
                    """, tenantId, matchType, subjectId, wanted, wanted));
        }
        else {
            out.addAll(jdbc.queryForList("""
                    select m.id, 'MATCH' as source, m.match_type as type, m.status, m.score as confidence, m.rationale,
                           m.evidence, m.decided_by, m.decided_at, m.created_at,
                           m.target_id, m.match_type as target_type
                    from document_matches m
                    where m.tenant_id = ? and m.document_id = ? and (? = '' or m.status = ?)
                    order by m.created_at desc
                    """, tenantId, subjectId, wanted, wanted));
            out.addAll(jdbc.queryForList("""
                    select r.id, 'RELATION' as source, r.relation_type as type, r.status, r.confidence, '{}'::jsonb as rationale,
                           r.evidence, r.decided_by, r.decided_at, r.created_at,
                           case when r.source_document_id = ? then r.target_document_id else r.source_document_id end as other_document_id,
                           case when r.source_document_id = ? then 'OUT' else 'IN' end as direction
                    from document_relations r
                    where r.tenant_id = ? and (r.source_document_id = ? or r.target_document_id = ?) and (? = '' or r.status = ?)
                    order by r.created_at desc
                    """, subjectId, subjectId, tenantId, subjectId, subjectId, wanted, wanted));
        }
        return out;
    }

    /** A person confirms a relationship. */
    @Transactional
    public Map<String, Object> confirm(UUID tenantId, UUID relationshipId, UUID userId) {
        return decide(tenantId, relationshipId, userId, DocumentMatch.Status.CONFIRMED);
    }

    /** A person rejects a relationship; it stays on record and is not suggested again. */
    @Transactional
    public Map<String, Object> reject(UUID tenantId, UUID relationshipId, UUID userId) {
        return decide(tenantId, relationshipId, userId, DocumentMatch.Status.REJECTED);
    }

    private Map<String, Object> decide(UUID tenantId, UUID id, UUID userId, DocumentMatch.Status decision) {
        Integer isMatch = jdbc.queryForObject("select count(*) from document_matches where id = ? and tenant_id = ?",
                Integer.class, id, tenantId);
        String source;
        if (isMatch != null && isMatch > 0) {
            // The matching service files the decision and, for a case, links the document.
            matching.decide(id, decision, userId);
            source = "MATCH";
        }
        else {
            int changed = jdbc.update("""
                    update document_relations set status = ?, decided_by = ?, decided_at = now()
                    where id = ? and tenant_id = ?
                    """, decision.name(), userId, id, tenantId);
            if (changed == 0) {
                throw ApiException.notFound("Verknüpfung");
            }
            source = "RELATION";
        }
        audit.record(AuditService.AuditEntry.builder(AuditEvent.Category.DOCUMENT,
                        decision == DocumentMatch.Status.CONFIRMED ? "RELATIONSHIP_CONFIRMED" : "RELATIONSHIP_REJECTED")
                .tenant(tenantId).entity(source.equals("MATCH") ? "document_matches" : "document_relations", id, null)
                .description("Relationship " + decision.name().toLowerCase(java.util.Locale.ROOT) + " by a person")
                .build());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("source", source);
        result.put("status", decision.name());
        return result;
    }
}
