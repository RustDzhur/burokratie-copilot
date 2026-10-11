package de.buerokratiecopilot.retention;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Retention, legal hold and the archive (spec §23, B-016).
 *
 * <p>This is the one place in the platform where the software could destroy a document, and the whole
 * design follows from that.</p>
 *
 * <p><strong>The platform never invents a retention period.</strong> How long a document must be kept
 * is a legal question, and the answer depends on the document and on the business. A policy therefore
 * carries a {@code legal_basis} — the sentence a person wrote down saying why — and stays unverified
 * until somebody confirms it. <strong>An unverified policy cannot cause a deletion</strong>, however
 * long its period is. Without that rule the platform would be quietly deleting documents on the basis
 * of a number nobody checked.</p>
 *
 * <p><strong>A legal hold blocks deletion.</strong> A document under hold is not deleted even when its
 * period has expired, and the job that tried says so in a reason a user can read. Lifting the hold is a
 * separate act by a person, and only then does the job run.</p>
 *
 * <p><strong>Deletion is soft.</strong> The document is marked deleted rather than removed: an archive
 * that cannot show what it used to hold is not an archive, and a deletion that turns out to have been
 * wrong has to be answerable.</p>
 */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    /** The actions the schema allows; only DELETE actually removes anything. */
    public static final List<String> ACTIONS = List.of("REVIEW", "ARCHIVE", "DELETE", "LEGAL_HOLD_CHECK",
            "NOTIFY");

    /** When the retention period starts counting. */
    public static final List<String> TRIGGERS = List.of("DOCUMENT_DATE", "RECEIPT_DATE", "CASE_CLOSED",
            "CONTRACT_END", "MANUAL");

    private final JdbcTemplate jdbc;

    public RetentionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A policy as it is defined. */
    public record PolicyRequest(UUID tenantId, String code, String nameGerman, UUID documentTypeId,
                                Integer retentionPeriodMonths, String retentionTrigger, String legalBasis,
                                LocalDate validFrom, UUID createdBy) {
    }

    /** What happened when a job ran. */
    public record Outcome(UUID jobId, String status, String reason) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("jobId", jobId.toString());
            map.put("status", status);
            map.put("reason", reason);
            return map;
        }
    }

    /**
     * Defines a retention policy.
     *
     * <p>The legal basis is required. A policy without one would be a period with no reason attached,
     * and the reason is the only thing that lets a later reader judge whether the period is right.</p>
     */
    @Transactional
    public UUID definePolicy(PolicyRequest request) {
        if (request.code() == null || request.code().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Eine Aufbewahrungsregel braucht einen Code");
        }
        if (request.legalBasis() == null || request.legalBasis().isBlank()) {
            // Not a formality: the platform is not entitled to invent a retention period, so it demands
            // the one thing it cannot produce itself.
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Eine Aufbewahrungsregel braucht eine Rechtsgrundlage. Die Plattform erfindet keine "
                            + "Aufbewahrungsfristen.");
        }
        if (request.retentionPeriodMonths() != null && request.retentionPeriodMonths() <= 0) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Die Aufbewahrungsfrist muss positiv sein");
        }
        String trigger = request.retentionTrigger() == null ? "DOCUMENT_DATE"
                : request.retentionTrigger().toUpperCase(java.util.Locale.ROOT);
        if (!TRIGGERS.contains(trigger)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Unbekannter Auslöser: " + trigger);
        }

        String name = "{\"de\":\"" + request.nameGerman().replace("\"", "'") + "\"}";
        return jdbc.queryForObject("""
                insert into document_retention_policies (id, tenant_id, code, name, document_type_id,
                                                         retention_period_months, retention_trigger,
                                                         legal_basis, requires_verification, version,
                                                         valid_from, created_by)
                values (gen_random_uuid(), ?, ?, ?::jsonb, ?, ?, ?, ?, true, 1, ?::timestamptz, ?)
                returning id
                """, UUID.class, request.tenantId(), request.code().strip(), name,
                request.documentTypeId(), request.retentionPeriodMonths(), trigger,
                request.legalBasis().strip(),
                java.sql.Timestamp.from(request.validFrom() == null ? Instant.now()
                        : request.validFrom().atStartOfDay().toInstant(ZoneOffset.UTC)),
                request.createdBy());
    }

    /** A person confirms that the policy's legal basis is right. Until then it deletes nothing. */
    @Transactional
    public void verifyPolicy(UUID tenantId, UUID policyId, UUID verifierId) {
        if (verifierId == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Eine Bestätigung braucht eine Person; sonst bestätigt niemand");
        }
        int updated = jdbc.update("""
                update document_retention_policies
                set requires_verification = false, verified_by = ?, verified_at = now(), updated_at = now()
                where id = ? and tenant_id = ?
                """, verifierId, policyId, tenantId);
        if (updated == 0) {
            throw ApiException.notFound("retention policy");
        }
        log.info("Retention policy {} verified by {}", policyId, verifierId);
    }

    /** Whether a policy may cause a deletion: it must be verified and in force. */
    @Transactional(readOnly = true)
    public boolean mayDelete(UUID tenantId, UUID policyId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select requires_verification, valid_from, valid_to from document_retention_policies
                where id = ? and tenant_id = ?
                """, policyId, tenantId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("retention policy");
        }
        Map<String, Object> row = rows.getFirst();
        if (Boolean.TRUE.equals(row.get("requires_verification"))) {
            return false;
        }
        Instant now = Instant.now();
        Instant from = row.get("valid_from") == null ? null
                : ((java.sql.Timestamp) row.get("valid_from")).toInstant();
        Instant to = row.get("valid_to") == null ? null
                : ((java.sql.Timestamp) row.get("valid_to")).toInstant();
        return (from == null || !now.isBefore(from)) && (to == null || now.isBefore(to));
    }

    /** Places a document under legal hold. */
    @Transactional
    public void placeLegalHold(UUID tenantId, UUID documentId, UUID userId, String note) {
        int updated = jdbc.update("""
                update documents set legal_hold = true, updated_at = now()
                where id = ? and tenant_id = ? and deleted_at is null
                """, documentId, tenantId);
        if (updated == 0) {
            throw ApiException.notFound("document");
        }
        audit(tenantId, userId, "LEGAL_HOLD_PLACED", documentId, note);
        log.info("Legal hold placed on document {} by {}", documentId, userId);
    }

    /** Lifts a legal hold, which is what allows a deletion that was previously blocked. */
    @Transactional
    public void liftLegalHold(UUID tenantId, UUID documentId, UUID userId, String note) {
        int updated = jdbc.update("""
                update documents set legal_hold = false, updated_at = now()
                where id = ? and tenant_id = ?
                """, documentId, tenantId);
        if (updated == 0) {
            throw ApiException.notFound("document");
        }
        audit(tenantId, userId, "LEGAL_HOLD_LIFTED", documentId, note);
        log.info("Legal hold lifted on document {} by {}", documentId, userId);
    }

    /**
     * Writes the audit entry for a legal hold change.
     *
     * <p>A hold is placed by a person and is meant to be answerable later, so the entry names the actor
     * and carries the note. The audit table is append-only, which is what makes the entry worth having.</p>
     */
    private void audit(UUID tenantId, UUID userId, String action, UUID documentId, String note) {
        jdbc.update("""
                insert into audit_events (id, tenant_id, actor_user_id, actor_type, category, action,
                                          entity_type, entity_id, description, changes, result, severity)
                values (gen_random_uuid(), ?, ?, 'USER', 'RETENTION', ?, 'document', ?, ?, ?::jsonb,
                        'SUCCESS', 'NOTICE')
                """, tenantId, userId, action, documentId, action,
                "{\"note\":\"" + (note == null ? "" : note.replace("\"", "'")) + "\"}");
    }

    /** Schedules an action for a document. */
    @Transactional
    public UUID schedule(UUID tenantId, UUID documentId, UUID policyId, String action,
                         LocalDate scheduledFor) {
        String normalized = action == null ? "REVIEW" : action.toUpperCase(java.util.Locale.ROOT);
        if (!ACTIONS.contains(normalized)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Unbekannte Aktion: " + normalized);
        }
        return jdbc.queryForObject("""
                insert into retention_jobs (id, tenant_id, document_id, policy_id, action, status,
                                            scheduled_for, details)
                values (gen_random_uuid(), ?, ?, ?, ?, 'SCHEDULED', ?::timestamptz, '{}'::jsonb)
                returning id
                """, UUID.class, tenantId, documentId, policyId, normalized,
                java.sql.Timestamp.from(scheduledFor == null ? Instant.now()
                        : scheduledFor.atStartOfDay().toInstant(ZoneOffset.UTC)));
    }

    /**
     * Runs a scheduled job.
     *
     * <p>Every reason not to act is checked here, in the order that matters, and each one produces a
     * {@code BLOCKED} job with a reason rather than a silent skip. A refusal a user cannot see is
     * indistinguishable from a platform that forgot.</p>
     */
    @Transactional
    public Outcome execute(UUID tenantId, UUID jobId, UUID userId) {
        Map<String, Object> job = jdbc.queryForMap("""
                select document_id, policy_id, action, status from retention_jobs
                where id = ? and tenant_id = ?
                """, jobId, tenantId);

        if (!"SCHEDULED".equals(String.valueOf(job.get("status")))) {
            throw ApiException.conflict("Diese Aufgabe ist nicht mehr geplant");
        }

        UUID documentId = (UUID) job.get("document_id");
        UUID policyId = (UUID) job.get("policy_id");
        String action = String.valueOf(job.get("action"));

        Map<String, Object> document = jdbc.queryForMap("""
                select legal_hold, deleted_at, archived_at from documents
                where id = ? and tenant_id = ?
                """, documentId, tenantId);

        if (Boolean.TRUE.equals(document.get("legal_hold"))) {
            // The acceptance criterion, and the reason legal hold exists: a document somebody may need
            // as evidence is not deleted because a counter reached its end.
            return block(tenantId, jobId, "LEGAL_HOLD",
                    "Das Dokument ist mit einem Legal Hold belegt und wird nicht gelöscht. Der Hold muss "
                            + "zuerst von einer Person aufgehoben werden.");
        }
        if (document.get("deleted_at") != null) {
            return block(tenantId, jobId, "ALREADY_DELETED", "Das Dokument ist bereits gelöscht");
        }

        if ("DELETE".equals(action)) {
            if (policyId == null) {
                return block(tenantId, jobId, "NO_POLICY",
                        "Ohne Aufbewahrungsregel wird nichts gelöscht");
            }
            if (!mayDelete(tenantId, policyId)) {
                // The platform does not delete on the strength of a period nobody confirmed.
                return block(tenantId, jobId, "POLICY_NOT_VERIFIED",
                        "Die Aufbewahrungsregel ist noch nicht durch eine Person bestätigt. Die Plattform "
                                + "löscht nicht aufgrund einer unbestätigten Frist.");
            }
            // Soft: the archive has to be able to show what it held, and a wrong deletion has to be
            // answerable.
            jdbc.update("""
                    update documents set deleted_at = now(), updated_at = now()
                    where id = ? and tenant_id = ?
                    """, documentId, tenantId);
        }
        else if ("ARCHIVE".equals(action)) {
            jdbc.update("""
                    update documents set archived_at = now(), updated_at = now()
                    where id = ? and tenant_id = ?
                    """, documentId, tenantId);
        }

        jdbc.update("""
                update retention_jobs set status = 'COMPLETED', executed_at = now(), blocked_reason = null,
                       updated_at = now()
                where id = ? and tenant_id = ?
                """, jobId, tenantId);

        log.info("Retention job {} ran action {} on document {}", jobId, action, documentId);
        return new Outcome(jobId, "COMPLETED", null);
    }

    private Outcome block(UUID tenantId, UUID jobId, String code, String reason) {
        jdbc.update("""
                update retention_jobs set status = 'BLOCKED', blocked_reason = ?, executed_at = now(),
                       details = ?::jsonb, updated_at = now()
                where id = ? and tenant_id = ?
                """, reason, "{\"code\":\"" + code + "\"}", jobId, tenantId);
        log.info("Retention job {} blocked: {}", jobId, code);
        return new Outcome(jobId, "BLOCKED", reason);
    }

    /** Documents whose retention period has expired and which nothing is holding. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> dueForDeletion(UUID tenantId) {
        return jdbc.queryForList("""
                select d.id, d.original_filename, p.code as policy_code, p.retention_period_months,
                       d.document_date, d.received_at
                from documents d
                join document_retention_policies p on p.id = d.retention_policy_id
                where d.tenant_id = ? and d.deleted_at is null
                  and d.legal_hold = false
                  and p.requires_verification = false
                  and p.retention_period_months is not null
                  and coalesce(d.document_date, d.received_at::date) + (p.retention_period_months
                      || ' months')::interval < now()
                """, tenantId);
    }

    /** Documents under legal hold, which is the list a person asks for before a deletion round. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> underLegalHold(UUID tenantId) {
        return jdbc.queryForList("""
                select id, original_filename, updated_at from documents
                where tenant_id = ? and legal_hold = true and deleted_at is null
                order by updated_at
                """, tenantId);
    }

    /** Whether a document is under legal hold; the question every deletion path has to ask first. */
    @Transactional(readOnly = true)
    public boolean isUnderLegalHold(UUID tenantId, UUID documentId) {
        Boolean held = jdbc.queryForObject("""
                select legal_hold from documents where id = ? and tenant_id = ?
                """, Boolean.class, documentId, tenantId);
        return Boolean.TRUE.equals(held);
    }
}
