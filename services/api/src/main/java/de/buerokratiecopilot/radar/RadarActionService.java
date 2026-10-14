package de.buerokratiecopilot.radar;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.audit.AuditService;
import de.buerokratiecopilot.audit.domain.AuditEvent;
import de.buerokratiecopilot.cases.CaseService;
import de.buerokratiecopilot.cases.domain.Case;
import de.buerokratiecopilot.shared.error.ApiException;

/**
 * What a person can do with a finding. Every action first checks that the person may see the finding (a finding of a
 * private case does not exist for everyone else), and repeating an action that already happened changes nothing.
 */
@Service
public class RadarActionService {

    private final RadarQueryService queries;
    private final RadarFindingService findings;
    private final RadarEngine engine;
    private final CaseService cases;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final jakarta.persistence.EntityManager entityManager;

    public RadarActionService(RadarQueryService queries, RadarFindingService findings, RadarEngine engine, CaseService cases,
                              JdbcTemplate jdbc, AuditService audit, jakarta.persistence.EntityManager entityManager) {
        this.entityManager = entityManager;
        this.queries = queries;
        this.findings = findings;
        this.engine = engine;
        this.cases = cases;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** Moves the finding to the target status; asking for the status it already has is not an error and records nothing. */
    @Transactional
    public Map<String, Object> decide(UUID tenantId, UUID userId, UUID findingId, RadarFindingStatus target, String reason,
                                      Instant snoozedUntil) {
        Map<String, Object> current = queries.requireVisible(tenantId, userId, findingId);
        if (RadarFindingStatus.valueOf((String) current.get("status")) != target) {
            findings.transition(tenantId, findingId, target, userId, reason, snoozedUntil);
        }
        return queries.detail(tenantId, userId, findingId);
    }

    @Transactional
    public Map<String, Object> assign(UUID tenantId, UUID userId, UUID findingId, UUID assignee) {
        queries.requireVisible(tenantId, userId, findingId);
        if (assignee != null) {
            Integer member = jdbc.queryForObject("select count(*) from memberships where tenant_id = ? and user_id = ? and status = 'ACTIVE'",
                    Integer.class, tenantId, assignee);
            if (member == null || member == 0) {
                throw ApiException.notFound("Benutzer");
            }
        }
        findings.assign(tenantId, findingId, assignee, userId);
        return queries.detail(tenantId, userId, findingId);
    }

    /** Opens a case for the finding and files its documents in it. A finding gets one case; asking again returns it. */
    @Transactional
    public Map<String, Object> createCase(UUID tenantId, UUID userId, UUID findingId) {
        Map<String, Object> finding = queries.requireVisible(tenantId, userId, findingId);
        if (finding.get("related_case_id") != null) {
            return queries.detail(tenantId, userId, findingId);
        }
        Case.Priority priority = switch (RadarSeverity.valueOf((String) finding.get("severity"))) {
            case CRITICAL -> Case.Priority.CRITICAL;
            case HIGH -> Case.Priority.HIGH;
            case MEDIUM -> Case.Priority.NORMAL;
            case LOW -> Case.Priority.LOW;
            case INFO -> Case.Priority.INFORMATIONAL;
        };
        Case created = cases.createWithRetry(new CaseService.CreateRequest(tenantId, (String) finding.get("title"), null,
                (String) finding.get("description"), priority, userId, (UUID) finding.get("related_contact_id"), null, null,
                null, List.of("radar")), userId);
        // The case was saved through JPA; the statements below are plain SQL and must see it.
        entityManager.flush();
        jdbc.update("update radar_findings set related_case_id = ?, updated_at = now() where id = ? and tenant_id = ?",
                created.getId(), findingId, tenantId);
        jdbc.update("""
                insert into case_documents (case_id, document_id, tenant_id, linked_by, link_origin, note)
                select ?, e.document_id, ?, ?, 'RULE', 'Beleg eines Radar-Befunds'
                from radar_finding_evidence e where e.finding_id = ? and e.document_id is not null
                on conflict (case_id, document_id) do nothing
                """, created.getId(), tenantId, userId, findingId);
        jdbc.update("""
                insert into radar_finding_events (tenant_id, finding_id, event_type, actor_user_id, reason, details)
                values (?, ?, 'CASE_CREATED', ?, null, jsonb_build_object('caseId', ?::text))
                """, tenantId, findingId, userId, created.getId().toString());
        audit.record(AuditService.AuditEntry.builder(AuditEvent.Category.CASE, "RADAR_FINDING_CASE_CREATED")
                .tenant(tenantId).entity("radar_findings", findingId, null).description("Case opened from a Radar finding").build());
        return queries.detail(tenantId, userId, findingId);
    }

    /** Runs the rules again over what the finding rests on, and returns what changed. */
    @Transactional
    public Map<String, Object> reEvaluate(UUID tenantId, UUID userId, UUID findingId) {
        queries.requireVisible(tenantId, userId, findingId);
        int created = 0;
        int reopened = 0;
        int updated = 0;
        for (UUID documentId : jdbc.queryForList(
                "select distinct document_id from radar_finding_evidence where finding_id = ? and document_id is not null",
                UUID.class, findingId)) {
            RadarEngine.Report report = engine.evaluateDocument(tenantId, documentId);
            created += report.created();
            reopened += report.reopened();
            updated += report.updated();
        }
        RadarEngine.Report sweep = engine.sweep(tenantId);
        created += sweep.created();
        reopened += sweep.reopened();
        updated += sweep.updated();
        Map<String, Object> result = queries.detail(tenantId, userId, findingId);
        result.put("reEvaluation", Map.of("created", created, "reopened", reopened, "updated", updated));
        return result;
    }
}
