package de.buerokratiecopilot.radar;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.audit.AuditService;
import de.buerokratiecopilot.audit.domain.AuditEvent;
import de.buerokratiecopilot.shared.error.ApiException;

/**
 * Findings of the Radar: raised, found again, decided on.
 *
 * <p>Two rules hold everything together. A situation has one finding, however often and however concurrently it is found
 * ({@code finding_key} is unique per tenant), and a finding changes status only along the moves of
 * {@link RadarFindingStatus}. A person's decision is not undone by the platform finding the same situation again; only new
 * significant data (a different {@code material_hash}) reopens a closed finding.</p>
 */
@Service
public class RadarFindingService {

    /** What an evaluation found, ready to be stored. The severity was computed by the rule, not by a model. */
    public record Draft(String key, String type, String ruleId, int ruleVersion, String title, String description,
                        RadarSeverity severity, Double confidence, UUID caseId, UUID contactId, UUID contractId,
                        List<String> reasonCodes, List<Map<String, Object>> recommendedActions,
                        List<Evidence> evidence, String materialHash) {
    }

    /** One thing the finding rests on. At least one of document, fact and clause is given. */
    public record Evidence(String role, UUID documentId, Integer page, String snippet, UUID factId, UUID clauseId) {
    }

    public enum Outcome {
        CREATED, UPDATED, REOPENED, UNCHANGED
    }

    public record Result(UUID id, Outcome outcome, RadarFindingStatus status) {
    }

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final ObjectMapper json;

    public RadarFindingService(JdbcTemplate jdbc, AuditService audit, ObjectMapper json) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.json = json;
    }

    /** Stores a finding, or recognises the one that already exists. Safe to call twice and from two threads. */
    @Transactional
    public Result raise(UUID tenantId, Draft draft) {
        UUID created = jdbc.query("""
                insert into radar_findings (tenant_id, finding_key, finding_type, rule_id, rule_version, title, description,
                        severity, confidence, related_case_id, related_contact_id, related_contract_id, reason_codes,
                        recommended_actions, material_hash)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                on conflict (tenant_id, finding_key) do nothing
                returning id
                """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
                tenantId, draft.key(), draft.type(), draft.ruleId(), draft.ruleVersion(), draft.title(), draft.description(),
                draft.severity().name(), draft.confidence(), draft.caseId(), draft.contactId(), draft.contractId(),
                toJson(draft.reasonCodes()), toJson(draft.recommendedActions()), draft.materialHash());
        if (created != null) {
            event(tenantId, created, "DETECTED", null, RadarFindingStatus.NEW, null, "Detected by " + draft.ruleId(),
                    Map.of("ruleVersion", draft.ruleVersion()));
            addEvidence(tenantId, created, draft.evidence());
            return new Result(created, Outcome.CREATED, RadarFindingStatus.NEW);
        }

        Map<String, Object> row = jdbc.queryForMap("""
                select id, status, material_hash from radar_findings where tenant_id = ? and finding_key = ? for update
                """, tenantId, draft.key());
        UUID id = (UUID) row.get("id");
        RadarFindingStatus status = RadarFindingStatus.valueOf((String) row.get("status"));
        String known = (String) row.get("material_hash");
        boolean changed = draft.materialHash() != null && !draft.materialHash().equals(known);

        if (!changed) {
            jdbc.update("update radar_findings set last_evaluated_at = now() where id = ?", id);
            return new Result(id, Outcome.UNCHANGED, status);
        }
        addEvidence(tenantId, id, draft.evidence());
        if (status.isClosed()) {
            jdbc.update("""
                    update radar_findings set status = 'REOPENED', resolved_at = null, resolution_reason = null,
                           reopen_count = reopen_count + 1, last_evaluated_at = now(), updated_at = now(),
                           title = ?, description = ?, severity = ?, confidence = ?, reason_codes = ?::jsonb,
                           recommended_actions = ?::jsonb, material_hash = ?
                    where id = ?
                    """, draft.title(), draft.description(), draft.severity().name(), draft.confidence(),
                    toJson(draft.reasonCodes()), toJson(draft.recommendedActions()), draft.materialHash(), id);
            event(tenantId, id, "REOPENED", status, RadarFindingStatus.REOPENED, null,
                    "New significant data after the finding was " + status.name().toLowerCase(java.util.Locale.ROOT),
                    Map.of("previousHash", String.valueOf(known), "newHash", draft.materialHash()));
            return new Result(id, Outcome.REOPENED, RadarFindingStatus.REOPENED);
        }
        jdbc.update("""
                update radar_findings set last_evaluated_at = now(), updated_at = now(), title = ?, description = ?,
                       severity = ?, confidence = ?, reason_codes = ?::jsonb, recommended_actions = ?::jsonb,
                       material_hash = ?
                where id = ?
                """, draft.title(), draft.description(), draft.severity().name(), draft.confidence(),
                toJson(draft.reasonCodes()), toJson(draft.recommendedActions()), draft.materialHash(), id);
        event(tenantId, id, "RE_EVALUATED", status, status, null, "The data changed", Map.of("newHash", draft.materialHash()));
        return new Result(id, Outcome.UPDATED, status);
    }

    /**
     * Says whether this is the first time anyone asks to announce the finding. The announcement is made once per finding,
     * however many evaluations find it again.
     */
    @Transactional
    public boolean claimNotification(UUID tenantId, UUID findingId) {
        int changed = jdbc.update("update radar_findings set notified_at = now() where id = ? and tenant_id = ? and notified_at is null",
                findingId, tenantId);
        if (changed == 1) {
            event(tenantId, findingId, "NOTIFIED", null, null, null, "Announced", Map.of());
        }
        return changed == 1;
    }

    /** A person (or the platform, for an expired snooze) moves the finding to another status. */
    @Transactional
    public RadarFindingStatus transition(UUID tenantId, UUID findingId, RadarFindingStatus target, UUID actor, String reason,
                                         Instant snoozedUntil) {
        String current = jdbc.query("select status from radar_findings where id = ? and tenant_id = ? for update",
                rs -> rs.next() ? rs.getString(1) : null, findingId, tenantId);
        if (current == null) {
            throw ApiException.notFound("Befund");
        }
        RadarFindingStatus from = RadarFindingStatus.valueOf(current);
        if (!from.canMoveTo(target)) {
            throw ApiException.invalidState("Ein Befund im Status " + from + " kann nicht in den Status " + target
                    + " wechseln");
        }
        if (target == RadarFindingStatus.SNOOZED && (snoozedUntil == null || !snoozedUntil.isAfter(Instant.now()))) {
            throw ApiException.invalidState("Zum Zurückstellen ist ein Zeitpunkt in der Zukunft nötig");
        }
        if ((target == RadarFindingStatus.DISMISSED || target == RadarFindingStatus.RESOLVED) && (reason == null || reason.isBlank())) {
            throw ApiException.invalidState("Ohne Begründung kann ein Befund nicht abgeschlossen werden");
        }
        boolean closing = target.isClosed();
        jdbc.update("""
                update radar_findings set status = ?, updated_at = now(),
                       snoozed_until = case when ? = 'SNOOZED' then ?::timestamptz else null end,
                       resolved_at = case when ? then now() else null end,
                       resolution_reason = case when ? then ? else null end,
                       reopen_count = reopen_count + case when ? = 'REOPENED' then 1 else 0 end
                where id = ? and tenant_id = ?
                """, target.name(), target.name(), snoozedUntil == null ? null : java.sql.Timestamp.from(snoozedUntil),
                closing, closing, reason, target.name(), findingId, tenantId);
        event(tenantId, findingId, target == RadarFindingStatus.REOPENED ? "REOPENED" : "STATUS_CHANGED", from, target, actor,
                reason, snoozedUntil == null ? Map.of() : Map.of("snoozedUntil", snoozedUntil.toString()));
        audit.record(AuditService.AuditEntry.builder(AuditEvent.Category.CASE, "RADAR_FINDING_" + target.name())
                .tenant(tenantId).entity("radar_findings", findingId, null)
                .description("Radar finding " + from + " -> " + target).build());
        return target;
    }

    @Transactional
    public void assign(UUID tenantId, UUID findingId, UUID assignee, UUID actor) {
        int changed = jdbc.update("update radar_findings set assigned_to = ?, updated_at = now() where id = ? and tenant_id = ?",
                assignee, findingId, tenantId);
        if (changed == 0) {
            throw ApiException.notFound("Befund");
        }
        event(tenantId, findingId, "ASSIGNED", null, null, actor, null, assignee == null ? Map.of() : Map.of("assignee", assignee.toString()));
    }

    /** Snoozes whose time is up come back for review. Returns how many. */
    @Transactional
    public int reopenExpiredSnoozes(UUID tenantId) {
        List<UUID> due = jdbc.queryForList("""
                select id from radar_findings where tenant_id = ? and status = 'SNOOZED' and snoozed_until <= now()
                """, UUID.class, tenantId);
        for (UUID id : due) {
            transition(tenantId, id, RadarFindingStatus.REOPENED, null, "The snooze time is up", null);
        }
        return due.size();
    }

    private void addEvidence(UUID tenantId, UUID findingId, List<Evidence> evidence) {
        if (evidence == null) {
            return;
        }
        for (Evidence item : evidence) {
            jdbc.update("""
                    insert into radar_finding_evidence (tenant_id, finding_id, role, document_id, page, snippet, fact_id,
                                                        contract_clause_id)
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict do nothing
                    """, tenantId, findingId, item.role() == null ? "PRIMARY" : item.role(), item.documentId(), item.page(),
                    item.snippet(), item.factId(), item.clauseId());
        }
    }

    private void event(UUID tenantId, UUID findingId, String type, RadarFindingStatus from, RadarFindingStatus to, UUID actor,
                       String reason, Map<String, ?> details) {
        jdbc.update("""
                insert into radar_finding_events (tenant_id, finding_id, event_type, from_status, to_status, actor_user_id,
                                                  reason, details)
                values (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """, tenantId, findingId, type, from == null ? null : from.name(), to == null ? null : to.name(), actor,
                reason == null ? null : (reason.length() > 500 ? reason.substring(0, 500) : reason), toJson(details));
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value == null ? List.of() : value);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("A finding could not be written as JSON", ex);
        }
    }
}
