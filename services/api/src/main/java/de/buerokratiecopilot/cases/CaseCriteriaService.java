package de.buerokratiecopilot.cases;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.cases.domain.Case;
import de.buerokratiecopilot.cases.domain.CaseEvent;
import de.buerokratiecopilot.cases.domain.CaseParticipant;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;
import de.buerokratiecopilot.shared.util.JsonColumns;

/**
 * The goal of a case and the checkable criteria for it being finished (tasks CA-01, CA-05).
 *
 * <p>Three kinds of criterion are checked by the platform itself from facts it holds: a task is done, a document of a
 * type has been received, a letter was <em>delivered</em> (the provider confirmed delivery; "sent" does not count). The
 * others are marked by a person, with a reference to what proves it, whose hash is kept. Nothing marks a criterion as met
 * because a document was read or a reminder created.</p>
 */
@Service
public class CaseCriteriaService {

    static final Set<String> KINDS = Set.of("TASK_DONE", "DOCUMENT_RECEIVED", "MESSAGE_DELIVERED", "REPLY_RECEIVED",
            "PAYMENT_CONFIRMED", "DECISION", "CUSTOM");

    /** What a criterion is about, when it is about a particular object. */
    public record Links(UUID taskId, UUID documentTypeId, UUID outboundMessageId) {
        public static Links none() {
            return new Links(null, null, null);
        }
    }

    private final JdbcTemplate jdbc;
    private final CaseRepository cases;
    private final CaseService caseService;

    public CaseCriteriaService(JdbcTemplate jdbc, CaseRepository cases, CaseService caseService) {
        this.jdbc = jdbc;
        this.cases = cases;
        this.caseService = caseService;
    }

    private Case writable(UUID caseId, UUID userId, boolean seeAll) {
        Case caseRecord = cases.findById(caseId).filter(c -> c.getDeletedAt() == null).orElseThrow(() -> ApiException.notFound("case"));
        caseService.requireAccess(caseRecord, userId, CaseParticipant.AccessLevel.WRITE, seeAll);
        return caseRecord;
    }

    /** Sets what the case is for and what happens next. */
    @Transactional
    public Map<String, Object> setGoal(UUID caseId, String goal, String nextAction, UUID userId, boolean seeAll) {
        Case caseRecord = writable(caseId, userId, seeAll);
        jdbc.update("update cases set goal = ?, next_action = ?, updated_at = now() where id = ?",
                blankToNull(goal), blankToNull(nextAction), caseId);
        caseService.record(caseRecord, CaseEvent.EventType.GOAL_SET, userId, "Ziel des Vorgangs festgelegt",
                Map.of("goal", goal == null ? "" : goal));
        return workspace(caseId);
    }

    @Transactional
    public Map<String, Object> addCriterion(UUID caseId, String kind, String description, Links links, UUID userId, boolean seeAll) {
        Case caseRecord = writable(caseId, userId, seeAll);
        if (kind == null || !KINDS.contains(kind)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Unknown criterion kind: " + kind);
        }
        if (description == null || description.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "A criterion needs a description");
        }
        if (kind.equals("TASK_DONE") && links.taskId() == null || kind.equals("MESSAGE_DELIVERED") && links.outboundMessageId() == null
                || kind.equals("DOCUMENT_RECEIVED") && links.documentTypeId() == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "The criterion " + kind + " must name what it is about");
        }
        UUID id = jdbc.queryForObject("""
                insert into case_completion_criteria (tenant_id, case_id, kind, description, task_id, document_type_id,
                                                      outbound_message_id, created_by, sort_order)
                values (?, ?, ?, ?, ?, ?, ?, ?, coalesce((select max(sort_order) + 10 from case_completion_criteria where case_id = ?), 100))
                returning id
                """, UUID.class, caseRecord.getTenantId(), caseId, kind, description.strip(), links.taskId(),
                links.documentTypeId(), links.outboundMessageId(), userId, caseId);
        caseService.record(caseRecord, CaseEvent.EventType.CRITERION_ADDED, userId, "Abschlusskriterium: " + description.strip(),
                Map.of("criterionId", id.toString(), "kind", kind));
        return workspace(caseId);
    }

    /** A person says the criterion is met and what proves it. Only kinds the platform cannot check itself may be marked so. */
    @Transactional
    public Map<String, Object> markMet(UUID caseId, UUID criterionId, String evidenceType, UUID evidenceId, String evidenceNote,
                                       UUID userId, boolean seeAll) {
        Case caseRecord = writable(caseId, userId, seeAll);
        Map<String, Object> criterion = criterion(caseId, criterionId);
        String kind = (String) criterion.get("kind");
        if (Set.of("TASK_DONE", "MESSAGE_DELIVERED", "DOCUMENT_RECEIVED").contains(kind)) {
            throw ApiException.conflict("The criterion " + kind + " is checked by the platform from its facts and cannot be marked by hand");
        }
        if ((evidenceId == null && (evidenceNote == null || evidenceNote.isBlank()))) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Marking a criterion as met needs the evidence: a document, a message or a note");
        }
        String hash = evidenceHash(caseRecord.getTenantId(), evidenceType, evidenceId, evidenceNote);
        jdbc.update("""
                update case_completion_criteria set status = 'MET', met_at = now(), met_by = ?,
                       evidence_ref = jsonb_build_object('type', ?::text, 'id', ?::text, 'note', ?::text), evidence_hash = ?
                where id = ? and case_id = ?
                """, userId, evidenceType, evidenceId == null ? null : evidenceId.toString(), evidenceNote, hash, criterionId, caseId);
        caseService.record(caseRecord, CaseEvent.EventType.CRITERION_MET, userId, "Kriterium erfüllt: " + criterion.get("description"),
                Map.of("criterionId", criterionId.toString(), "evidenceHash", hash));
        return workspace(caseId);
    }

    /** Giving up a criterion is a decision: it needs a person and words. */
    @Transactional
    public Map<String, Object> waive(UUID caseId, UUID criterionId, String reason, UUID userId, boolean seeAll) {
        Case caseRecord = writable(caseId, userId, seeAll);
        Map<String, Object> criterion = criterion(caseId, criterionId);
        if (reason == null || reason.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Giving up a criterion needs a reason");
        }
        jdbc.update("update case_completion_criteria set status = 'WAIVED', waived_reason = ?, met_by = ? where id = ? and case_id = ?",
                reason.strip(), userId, criterionId, caseId);
        caseService.record(caseRecord, CaseEvent.EventType.CRITERION_WAIVED, userId, "Kriterium entfallen: " + criterion.get("description"),
                Map.of("criterionId", criterionId.toString(), "reason", reason.strip()));
        return workspace(caseId);
    }

    /**
     * Looks at the facts and marks the criteria the platform can check: a done task, a received document type, a delivered
     * letter. A met criterion is never reopened by this; only a person does that.
     */
    @Transactional
    public int evaluate(UUID caseId) {
        int changed = jdbc.update("""
                update case_completion_criteria c set status = 'MET', met_at = now(),
                       evidence_ref = jsonb_build_object('type', 'TASK', 'id', c.task_id::text),
                       evidence_hash = encode(sha256(convert_to('TASK:' || c.task_id::text, 'UTF8')), 'hex')
                where c.case_id = ? and c.status = 'OPEN' and c.kind = 'TASK_DONE'
                  and exists (select 1 from tasks t where t.id = c.task_id and t.status = 'DONE' and t.deleted_at is null)
                """, caseId);
        changed += jdbc.update("""
                update case_completion_criteria c set status = 'MET', met_at = now(),
                       evidence_ref = jsonb_build_object('type', 'DOCUMENT', 'id', d.id::text),
                       evidence_hash = d.content_hash
                from documents d
                where c.case_id = ? and c.status = 'OPEN' and c.kind = 'DOCUMENT_RECEIVED'
                  and d.id = (select d2.id from case_documents cd join documents d2 on d2.id = cd.document_id
                              where cd.case_id = c.case_id and d2.document_type_id = c.document_type_id and d2.deleted_at is null
                              order by cd.created_at limit 1)
                """, caseId);
        changed += jdbc.update("""
                update case_completion_criteria c set status = 'MET', met_at = now(),
                       evidence_ref = jsonb_build_object('type', 'MESSAGE', 'id', c.outbound_message_id::text),
                       evidence_hash = encode(sha256(convert_to('MESSAGE:' || c.outbound_message_id::text, 'UTF8')), 'hex')
                where c.case_id = ? and c.status = 'OPEN' and c.kind = 'MESSAGE_DELIVERED'
                  and exists (select 1 from message_delivery_events e where e.outbound_message_id = c.outbound_message_id and e.proves_delivery)
                """, caseId);
        return changed;
    }

    /** The criteria that are neither met nor given up, as last evaluated (reading only; see {@link #evaluate}). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> open(UUID caseId) {
        return jdbc.queryForList("select id, kind, description from case_completion_criteria where case_id = ? and status = 'OPEN' order by sort_order",
                caseId);
    }

    /** Goal, next action and criteria, the way the case page shows them. */
    @Transactional
    public Map<String, Object> workspace(UUID caseId) {
        evaluate(caseId);
        Map<String, Object> result = new LinkedHashMap<>(jdbc.queryForMap("select goal, next_action from cases where id = ?", caseId));
        result.put("criteria", JsonColumns.parse(jdbc.queryForList("""
                select id, kind, description, status, task_id, document_type_id, outbound_message_id, evidence_ref, evidence_hash,
                       met_at, met_by, waived_reason
                from case_completion_criteria where case_id = ? order by sort_order
                """, caseId)));
        return result;
    }

    private Map<String, Object> criterion(UUID caseId, UUID criterionId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select kind, description, status from case_completion_criteria where id = ? and case_id = ?", criterionId, caseId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("criterion");
        }
        return rows.get(0);
    }

    private String evidenceHash(UUID tenantId, String type, UUID id, String note) {
        String basis = "NOTE:" + (note == null ? "" : note.strip());
        if (id != null && "DOCUMENT".equals(type)) {
            List<String> hashes = jdbc.queryForList("select content_hash from documents where id = ? and tenant_id = ?", String.class, id, tenantId);
            if (hashes.isEmpty()) {
                throw ApiException.notFound("document");
            }
            basis = "DOCUMENT:" + hashes.get(0);
        }
        else if (id != null) {
            basis = String.valueOf(type) + ":" + id + ":" + (note == null ? "" : note.strip());
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(basis.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is part of every Java platform", ex);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
