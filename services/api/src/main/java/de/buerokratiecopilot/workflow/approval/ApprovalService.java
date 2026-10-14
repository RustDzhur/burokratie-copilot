package de.buerokratiecopilot.workflow.approval;

import java.math.BigDecimal;
import java.time.Instant;
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
 * Approvals, authority and escalation (spec §32, F-003).
 *
 * <p>The acceptance criterion is that an action of the highest level is impossible without the
 * confirmation of an authorised person. Three rules make that true rather than aspirational.</p>
 *
 * <p><strong>The authority matrix is data.</strong> Who may approve what, up to which amount and how many
 * confirmations are needed lives in {@code payment_approval_rules}, which a customer configures. A matrix
 * written into the code would be this platform deciding who is allowed to spend a company's money — a
 * rule nobody agreed to and that the first customer would need changed.</p>
 *
 * <p><strong>Nobody approves their own request.</strong> A person who could would be a person for whom the
 * approval step does not exist, and the whole point of a second signature is that it is somebody else's.</p>
 *
 * <p><strong>Some levels need more than one.</strong> Where a rule says so, one approval is not enough: the
 * decision stays pending until the required number of different people have agreed. Counting approvals
 * rather than people would let the same person click twice.</p>
 *
 * <p><strong>An overdue request escalates rather than waits forever.</strong> Silence is not agreement,
 * and a request nobody answered must end up somewhere visible instead of blocking a process silently.</p>
 */
@Service
public class ApprovalService {

    private static final Logger LOG = LoggerFactory.getLogger(ApprovalService.class);

    private final JdbcTemplate jdbc;
    private final ApprovalBinding binding;

    public ApprovalService(JdbcTemplate jdbc, ApprovalBinding binding) {
        this.jdbc = jdbc;
        this.binding = binding;
    }

    /** A rule of the authority matrix, as the customer configured it. */
    public record Rule(String requiredRole, String requiredPermission, int levels,
                       boolean secondPerson, BigDecimal minAmount, BigDecimal maxAmount) {
    }

    /** An approval request. */
    public record Request(UUID approvalId, String status, int levelsRequired, int levelsGiven,
                          String requiredRole, String requiredPermission) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("approvalId", approvalId.toString());
            map.put("status", status);
            map.put("levelsRequired", levelsRequired);
            map.put("levelsGiven", levelsGiven);
            map.put("requiredRole", requiredRole);
            map.put("requiredPermission", requiredPermission);
            return map;
        }
    }

    /** The outcome of a decision. */
    public record Decision(String status, int levelsGiven, int levelsRequired, String note) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("status", status);
            map.put("levelsGiven", levelsGiven);
            map.put("levelsRequired", levelsRequired);
            map.put("note", note);
            return map;
        }
    }

    /**
     * Which rule applies to an amount and a direction.
     *
     * <p>The most specific rule wins: a rule naming a role beats one that names only a permission, and a
     * narrower amount range beats a wider one. Without an order, two matching rules would make the
     * required authority depend on the order rows happen to come back in.</p>
     */
    @Transactional(readOnly = true)
    public Rule ruleFor(UUID tenantId, BigDecimal amount, String direction) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select required_role, required_permission, approval_levels, require_second_person,
                       min_amount, max_amount
                from payment_approval_rules
                where tenant_id = ? and is_active
                  and (direction = ? or direction = 'ANY')
                  and (min_amount is null or min_amount <= ?)
                  and (max_amount is null or max_amount >= ?)
                order by (required_role is null), (max_amount is null), max_amount, created_at
                limit 1
                """, tenantId, direction, amount, amount);

        if (rows.isEmpty()) {
            // No rule means nobody has said what this needs, so it goes to a person rather than through.
            // Defaulting to "no approval needed" would be the platform allowing a payment nobody authorised.
            return new Rule(null, null, 1, false, null, null);
        }
        Map<String, Object> row = rows.getFirst();
        return new Rule((String) row.get("required_role"), (String) row.get("required_permission"),
                ((Number) row.get("approval_levels")).intValue(),
                Boolean.TRUE.equals(row.get("require_second_person")),
                (BigDecimal) row.get("min_amount"), (BigDecimal) row.get("max_amount"));
    }

    /**
     * Raises an approval request.
     *
     * <p>The rule that applies is copied onto the request, so a later change to the matrix does not move
     * the bar for a decision somebody is already considering.</p>
     */
    @Transactional
    public Request request(UUID tenantId, String subjectType, UUID subjectId, String approvalType,
                           String title, String justification, BigDecimal amount, String direction,
                           Instant expiresAt, UUID requestedBy) {
        Rule rule = ruleFor(tenantId, amount == null ? BigDecimal.ZERO : amount, direction);

        UUID approvalId = jdbc.queryForObject("""
                insert into approvals (id, tenant_id, subject_type, subject_id, approval_type, status,
                                       automation_level, title, justification, requested_by,
                                       required_permission, required_role, expires_at, metadata)
                values (gen_random_uuid(), ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                returning id
                """, UUID.class, tenantId, subjectType, subjectId,
                approvalType == null ? "PAYMENT" : approvalType.toUpperCase(java.util.Locale.ROOT),
                rule.levels(), title, justification, requestedBy, rule.requiredPermission(),
                rule.requiredRole(), expiresAt == null ? null : java.sql.Timestamp.from(expiresAt),
                "{\"levelsRequired\":" + rule.levels() + ",\"secondPerson\":" + rule.secondPerson() + "}");

        binding.of(tenantId, subjectType, subjectId).ifPresent(fingerprint -> jdbc.update(
                "update approvals set content_hash = ?, subject_version = ? where id = ? and tenant_id = ?",
                fingerprint.hash(), fingerprint.version(), approvalId, tenantId));
        journal(tenantId, approvalId, "REQUESTED", requestedBy, justification);
        LOG.info("Approval {} requested in tenant {} (level {}, role {})", approvalId, tenantId,
                rule.levels(), rule.requiredRole());
        return new Request(approvalId, "PENDING", rule.levels(), 0, rule.requiredRole(),
                rule.requiredPermission());
    }

    /**
     * Decides an approval.
     *
     * <p>Refused when the person is the requester, when they lack the required authority, or when they
     * have already given their approval to this request.</p>
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Decision decide(UUID tenantId, UUID approvalId, UUID deciderId, boolean approved, String note) {
        return decide(tenantId, approvalId, deciderId, approved, note, false);
    }

    /**
     * As {@link #decide(UUID, UUID, UUID, boolean, String)}; with {@code changesRequested} the answer is not a refusal of the
     * thing itself but a request to change it: it is recorded as a rejection of this version of the material, with the wish in
     * the note, and the requester is expected to raise a new request for the changed material.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Decision decide(UUID tenantId, UUID approvalId, UUID deciderId, boolean approved, String note, boolean changesRequested) {
        if (changesRequested && (note == null || note.isBlank())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Eine Änderung zu erbitten verlangt eine Beschreibung");
        }
        if (changesRequested) {
            note = "[Änderung erbeten] " + note.strip();
            approved = false;
        }
        Map<String, Object> approval = requireApproval(tenantId, approvalId);
        String status = String.valueOf(approval.get("status"));
        if (!"PENDING".equals(status)) {
            throw ApiException.conflict("Diese Freigabe ist nicht mehr offen (" + status + ")");
        }
        if (deciderId == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Eine Freigabe braucht eine Person; sonst gibt niemand frei");
        }
        if (deciderId.equals(approval.get("requested_by"))) {
            // A person who could approve their own request is a person for whom the step does not exist.
            throw ApiException.forbidden("Niemand kann die eigene Anfrage freigeben");
        }
        if (approved) {
            // What the approver is about to confirm must still be what was requested.
            requireStillBound(tenantId, approvalId, approval);
        }

        String requiredPermission = (String) approval.get("required_permission");
        String requiredRole = (String) approval.get("required_role");
        if (!hasAuthority(tenantId, deciderId, requiredPermission, requiredRole)) {
            throw ApiException.forbidden("Diese Person ist für diese Freigabe nicht befugt");
        }

        int levelsRequired = levelsRequiredOf(approval);
        List<UUID> alreadyDecided = distinctApprovers(tenantId, approvalId);
        if (alreadyDecided.contains(deciderId)) {
            throw ApiException.conflict("Diese Person hat bereits entschieden");
        }

        journal(tenantId, approvalId, approved ? "APPROVED" : "REJECTED", deciderId, note);

        if (!approved) {
            // A rejection needs one person: continuing to collect agreement after somebody has said no
            // would be collecting agreement for something that is not going to happen.
            jdbc.update("""
                    update approvals set status = 'REJECTED', decided_by = ?, decided_at = now(),
                           decision_note = ?, updated_at = now()
                    where id = ? and tenant_id = ?
                    """, deciderId, note, approvalId, tenantId);
            return new Decision("REJECTED", alreadyDecided.size() + 1, levelsRequired, note);
        }

        int given = alreadyDecided.size() + 1;
        if (given < levelsRequired) {
            // Still pending: the number of different people is what counts, so the same person clicking
            // twice cannot finish a decision that needs two.
            journal(tenantId, approvalId, "AWAITING_FURTHER_APPROVAL", deciderId,
                    "Noch " + (levelsRequired - given) + " Freigabe(n) erforderlich");
            return new Decision("PENDING", given, levelsRequired,
                    "Noch " + (levelsRequired - given) + " Freigabe(n) erforderlich");
        }

        jdbc.update("""
                update approvals set status = 'APPROVED', decided_by = ?, decided_at = now(),
                       decision_note = ?, updated_at = now()
                where id = ? and tenant_id = ?
                """, deciderId, note, approvalId, tenantId);
        return new Decision("APPROVED", given, levelsRequired, note);
    }

    /**
     * Withdraws a request that is no longer needed.
     *
     * <p><strong>A withdrawal is not a decision.</strong> The schema says so and is right: {@code decided_at}
     * and {@code decided_by} may only be set when the status is APPROVED or REJECTED, because those columns
     * name a person who decided something. Nobody decided here — the requester took their own request back —
     * and writing a name into them would put somebody's name on a decision they never made.</p>
     *
     * <p>The reason is kept in the journal, which is where the history of the request lives, rather than in
     * a column that belongs to a decision.</p>
     */
    @Transactional
    public void cancel(UUID tenantId, UUID approvalId, UUID cancelledBy, String reason) {
        requireApproval(tenantId, approvalId);
        int updated = jdbc.update("""
                update approvals set status = 'CANCELLED', updated_at = now()
                where id = ? and tenant_id = ? and status = 'PENDING'
                """, approvalId, tenantId);
        if (updated == 0) {
            throw ApiException.conflict("Diese Freigabe ist nicht mehr offen");
        }
        journal(tenantId, approvalId, "CANCELLED", cancelledBy, reason);
    }

    /**
     * Escalates the requests nobody answered.
     *
     * <p>Silence is not agreement: an overdue request is marked expired and an escalation is recorded, so
     * it ends up visible instead of blocking a process quietly.</p>
     */
    @Transactional
    public List<UUID> escalateOverdue(UUID tenantId) {
        List<UUID> overdue = jdbc.query("""
                select id from approvals
                where tenant_id = ? and status = 'PENDING' and expires_at is not null and expires_at < now()
                order by expires_at
                """, (rs, row) -> rs.getObject("id", UUID.class), tenantId);

        for (UUID approvalId : overdue) {
            jdbc.update("""
                    update approvals set status = 'EXPIRED', updated_at = now()
                    where id = ? and tenant_id = ? and status = 'PENDING'
                    """, approvalId, tenantId);
            journal(tenantId, approvalId, "ESCALATED", null,
                    "Die Frist ist abgelaufen, ohne dass jemand entschieden hat");
        }
        return overdue;
    }

    /**
     * Checks that the material of an approval is still what was requested. If it changed, the approval expires (a decision
     * already given stays on record) and a new request is raised for the changed material; the caller is told with a conflict.
     */
    private void requireStillBound(UUID tenantId, UUID approvalId, Map<String, Object> approval) {
        String recorded = (String) approval.get("content_hash");
        if (recorded == null) {
            return; // an approval of a subject without a fingerprint (a role change, a connection) is not bound to content
        }
        String type = String.valueOf(approval.get("subject_type"));
        UUID subjectId = (UUID) approval.get("subject_id");
        ApprovalBinding.Fingerprint now = binding.of(tenantId, type, subjectId).orElse(null);
        if (now != null && now.hash().equals(recorded)) {
            return;
        }
        jdbc.update("update approvals set status = 'EXPIRED', updated_at = now() where id = ? and tenant_id = ? and status in ('PENDING', 'APPROVED')",
                approvalId, tenantId);
        journal(tenantId, approvalId, "ESCALATED", null, "Der Inhalt hat sich nach der Anfrage geändert; die Freigabe ist erloschen");
        UUID renewed = now == null ? null : renew(tenantId, approvalId, (UUID) approval.get("requested_by"), now);
        throw ApiException.conflict("Der Inhalt hat sich geändert, seit die Freigabe angefragt wurde. Die alte Freigabe ist erloschen"
                + (renewed == null ? "." : "; eine neue Anfrage wurde erstellt (" + renewed + ")."));
    }

    private UUID renew(UUID tenantId, UUID oldApprovalId, UUID requestedBy, ApprovalBinding.Fingerprint now) {
        UUID id = jdbc.queryForObject("""
                insert into approvals (tenant_id, subject_type, subject_id, approval_type, status, automation_level, title, justification,
                                       requested_by, required_permission, required_role, metadata, content_hash, subject_version)
                select tenant_id, subject_type, subject_id, approval_type, 'PENDING', automation_level, title,
                       'Erneute Anfrage: der Inhalt hat sich nach der ersten Anfrage geändert', requested_by, required_permission,
                       required_role, metadata, ?, ?
                from approvals where id = ? and tenant_id = ?
                returning id
                """, UUID.class, now.hash(), now.version(), oldApprovalId, tenantId);
        journal(tenantId, id, "REQUESTED", requestedBy, "Erneute Anfrage zu " + oldApprovalId);
        return id;
    }

    /**
     * Called by whatever executes the approved action: nothing is executed on a confirmation that was given for other content.
     *
     * <p>If the subject has approvals bound to a fingerprint, one of them must be an approval of the content as it is now.
     * Otherwise the approvals given for the earlier content expire, a new request is raised for the current content (unless
     * one is already waiting), a release or confirmation recorded on the subject itself is withdrawn, and the caller is
     * refused. Runs in its own transaction: the expiry and the new request must survive the refusal.</p>
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW, noRollbackFor = ApiException.class)
    public void requireBoundToCurrentContent(UUID tenantId, String subjectType, UUID subjectId) {
        ApprovalBinding.Fingerprint now = binding.of(tenantId, subjectType, subjectId).orElse(null);
        if (now == null) {
            return;
        }
        // A release recorded on the subject itself (a letter's approved_by, a payment's confirmation).
        String recorded = "OUTBOUND_MESSAGE".equals(subjectType)
                ? jdbc.query("select approved_content_hash from outbound_messages where id = ? and tenant_id = ?",
                        rs -> rs.next() ? rs.getString(1) : null, subjectId, tenantId)
                : jdbc.query("select approved_content_hash from payments where id = ? and tenant_id = ?",
                        rs -> rs.next() ? rs.getString(1) : null, subjectId, tenantId);
        boolean subjectChanged = recorded != null && !recorded.equals(now.hash());
        if (subjectChanged) {
            if ("OUTBOUND_MESSAGE".equals(subjectType)) {
                jdbc.update("""
                        update outbound_messages set status = 'PENDING_APPROVAL', approved_by = null, approved_at = null,
                               approved_content_hash = null, updated_at = now()
                        where id = ? and tenant_id = ? and status in ('APPROVED', 'QUEUED')
                        """, subjectId, tenantId);
            }
            else {
                jdbc.update("""
                        update payments set status = 'AWAITING_APPROVAL', confirmed_by = null, confirmed_at = null,
                               approved_content_hash = null, updated_at = now()
                        where id = ? and tenant_id = ? and status = 'APPROVED'
                        """, subjectId, tenantId);
            }
        }

        Integer bound = jdbc.queryForObject("select count(*) from approvals where tenant_id = ? and subject_type = ? and subject_id = ? and content_hash is not null",
                Integer.class, tenantId, subjectType, subjectId);
        Integer matching = jdbc.queryForObject("""
                select count(*) from approvals where tenant_id = ? and subject_type = ? and subject_id = ? and status = 'APPROVED'
                  and content_hash = ?
                """, Integer.class, tenantId, subjectType, subjectId, now.hash());
        boolean approvalsMissing = bound != null && bound > 0 && (matching == null || matching == 0);
        if (!subjectChanged && !approvalsMissing) {
            return;
        }

        List<Map<String, Object>> stale = jdbc.queryForList("""
                select id, requested_by, title from approvals
                where tenant_id = ? and subject_type = ? and subject_id = ? and status = 'APPROVED' and content_hash is not null
                  and content_hash <> ?
                """, tenantId, subjectType, subjectId, now.hash());
        UUID renewed = null;
        for (Map<String, Object> approval : stale) {
            UUID id = (UUID) approval.get("id");
            jdbc.update("update approvals set status = 'EXPIRED', updated_at = now() where id = ? and tenant_id = ? and status = 'APPROVED'",
                    id, tenantId);
            journal(tenantId, id, "ESCALATED", null, "Der Inhalt hat sich nach der Freigabe geändert; die Freigabe ist erloschen");
        }
        Integer waiting = jdbc.queryForObject("""
                select count(*) from approvals where tenant_id = ? and subject_type = ? and subject_id = ? and status = 'PENDING'
                  and content_hash = ?
                """, Integer.class, tenantId, subjectType, subjectId, now.hash());
        if (!stale.isEmpty() && (waiting == null || waiting == 0)) {
            renewed = renew(tenantId, (UUID) stale.get(0).get("id"), (UUID) stale.get(0).get("requested_by"), now);
        }
        throw ApiException.conflict("Für den aktuellen Inhalt liegt keine gültige Freigabe vor: er hat sich nach der Freigabe geändert."
                + (renewed == null ? "" : " Eine neue Anfrage wurde erstellt (" + renewed + ")."));
    }

    /** Records an escalation that a person asked for, without changing the decision. */
    @Transactional
    public void escalate(UUID tenantId, UUID approvalId, UUID actorId, String reason) {
        requireApproval(tenantId, approvalId);
        journal(tenantId, approvalId, "ESCALATED", actorId, reason);
    }

    /** Whether a person may decide a request, which is the question the acceptance criterion asks. */
    @Transactional(readOnly = true)
    public boolean mayDecide(UUID tenantId, UUID approvalId, UUID userId) {
        Map<String, Object> approval = requireApproval(tenantId, approvalId);
        if (!"PENDING".equals(String.valueOf(approval.get("status"))) || userId == null
                || userId.equals(approval.get("requested_by"))) {
            return false;
        }
        return hasAuthority(tenantId, userId, (String) approval.get("required_permission"),
                (String) approval.get("required_role"));
    }

    /** The decisions and escalations of a request. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> history(UUID tenantId, UUID approvalId) {
        requireApproval(tenantId, approvalId);
        return jdbc.queryForList("""
                select event_type, actor_user_id, comment, created_at from approval_events
                where approval_id = ? and tenant_id = ? order by created_at
                """, approvalId, tenantId);
    }

    /** The open requests of a tenant. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> pending(UUID tenantId) {
        return jdbc.queryForList("""
                select id, status, subject_type, subject_id, approval_type, title, required_role,
                       required_permission, requested_by, expires_at, automation_level, metadata
                from approvals where tenant_id = ? and status = 'PENDING' order by created_at
                """, tenantId);
    }

    // ------------------------------------------------------------------ internals

    /**
     * Whether a person holds the authority a rule demands.
     *
     * <p>Either the required permission or the required role is enough: a rule naming a permission says
     * what somebody may do, and a rule naming a role says who they are, and a customer may reasonably
     * write either.</p>
     */
    private boolean hasAuthority(UUID tenantId, UUID userId, String permission, String role) {
        if (permission == null && role == null) {
            // No authority was demanded, so membership is the only requirement. Reached when a customer
            // has configured no rule at all.
            Integer member = jdbc.queryForObject("""
                    select count(*) from memberships where tenant_id = ? and user_id = ? and status = 'ACTIVE'
                    """, Integer.class, tenantId, userId);
            return member != null && member > 0;
        }

        Integer count = jdbc.queryForObject("""
                select count(*) from memberships m
                where m.tenant_id = ? and m.user_id = ? and m.status = 'ACTIVE'
                  and exists (
                      select 1 from membership_roles mr
                      join role_permissions rp on rp.role_id = mr.role_id
                      join permissions p on p.id = rp.permission_id
                      join roles r on r.id = mr.role_id
                      where mr.membership_id = m.id
                        and (mr.expires_at is null or mr.expires_at > now())
                        and (?::text is null or p.code = ?)
                        and (?::text is null or r.code = ?)
                  )
                """, Integer.class, tenantId, userId, permission, permission, role, role);
        return count != null && count > 0;
    }

    /** How many confirmations the request needs, read from the copy made when it was raised. */
    private int levelsRequiredOf(Map<String, Object> approval) {
        Object metadata = approval.get("metadata");
        if (metadata instanceof String text && text.contains("levelsRequired")) {
            try {
                Map<?, ?> map = (Map<?, ?>) de.buerokratiecopilot.workflow.WorkflowService.readJson(text);
                Object value = map.get("levelsRequired");
                if (value instanceof Number number) {
                    return number.intValue();
                }
            }
            catch (RuntimeException ex) {
                LOG.debug("Metadaten der Freigabe nicht lesbar", ex);
            }
        }
        Object level = approval.get("automation_level");
        return level instanceof Number number ? Math.max(1, number.intValue()) : 1;
    }

    /** How many different people have already agreed. */
    private List<UUID> distinctApprovers(UUID tenantId, UUID approvalId) {
        return jdbc.query("""
                select distinct actor_user_id from approval_events
                where approval_id = ? and tenant_id = ? and event_type = 'APPROVED'
                  and actor_user_id is not null
                """, (rs, row) -> rs.getObject("actor_user_id", UUID.class), approvalId, tenantId);
    }

    private Map<String, Object> requireApproval(UUID tenantId, UUID approvalId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, status, requested_by, required_permission, required_role, automation_level,
                       metadata::text as metadata, subject_type, subject_id, content_hash, subject_version, title, approval_type
                from approvals where id = ? and tenant_id = ?
                """, approvalId, tenantId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("approval");
        }
        return rows.getFirst();
    }

    private void journal(UUID tenantId, UUID approvalId, String eventType, UUID actorId, String comment) {
        jdbc.update("""
                insert into approval_events (id, approval_id, tenant_id, event_type, actor_user_id, comment)
                values (gen_random_uuid(), ?, ?, ?, ?, ?)
                """, approvalId, tenantId, eventType, actorId, comment);
    }
}
