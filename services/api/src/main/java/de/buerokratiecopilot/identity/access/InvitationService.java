package de.buerokratiecopilot.identity.access;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
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
 * Invitations and temporary access (spec §33, A-017).
 *
 * <p>Three ideas hold this together.</p>
 *
 * <p><strong>An invitation is a token that is shown once and stored hashed.</strong> It is a credential
 * like any other: whoever holds it can join the tenant, so a platform that kept it could hand out
 * membership from its own database.</p>
 *
 * <p><strong>Every change of access is recorded.</strong> Who invited whom, who accepted, who was
 * suspended, who granted a temporary right to a case and until when. Access that changes without a record
 * is access nobody can account for later, and the question "how did this person get to see this file" is
 * asked precisely when something has gone wrong.</p>
 *
 * <p><strong>Temporary access expires by itself.</strong> A participant entry with an end date stops
 * granting anything once the date has passed, without anybody having to remember to remove it. Access that
 * depends on somebody remembering is access that outlives its reason.</p>
 */
@Service
public class InvitationService {

    private static final Logger log = LoggerFactory.getLogger(InvitationService.class);

    /** The access levels the schema allows, weakest first. */
    private static final List<String> ACCESS_LEVELS = List.of("READ", "COMMENT", "WRITE", "APPROVE", "FULL");

    /** The roles a participant may have on a case. */
    private static final List<String> PARTICIPANT_ROLES = List.of("OWNER", "RESPONSIBLE", "PARTICIPANT",
            "OBSERVER", "APPROVER", "EXTERNAL_ADVISOR", "LEGAL_ADVISOR", "COUNTERPARTY");

    /** How long an invitation lasts when the caller does not say. */
    private static final long DEFAULT_INVITATION_DAYS = 14;

    private final JdbcTemplate jdbc;
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;

    public InvitationService(JdbcTemplate jdbc,
                             org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactionManager = transactionManager;
    }

    /** A new invitation, with the token that exists only in this answer. */
    public record Invitation(UUID invitationId, String email, String token, Instant expiresAt) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("invitationId", invitationId.toString());
            map.put("email", email);
            map.put("token", token);
            map.put("expiresAt", expiresAt.toString());
            map.put("note", "Dieser Link wird nur einmal angezeigt; gespeichert wird nur sein Hash.");
            return map;
        }
    }

    /** What a member may do with a case. */
    public record CaseAccess(String role, String accessLevel, Instant expiresAt, boolean active) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("role", role);
            map.put("accessLevel", accessLevel);
            map.put("expiresAt", expiresAt == null ? null : expiresAt.toString());
            map.put("active", active);
            return map;
        }
    }

    // ---------------------------------------------------------------- invitations

    /**
     * Invites somebody to the tenant.
     *
     * <p>Only one invitation per address may be pending: the schema says so with a partial unique index,
     * and it is the right rule — two live invitation links for one address is one link too many, and
     * nobody knows which one was used.</p>
     */
    @Transactional
    public Invitation invite(UUID tenantId, String email, UUID roleId, UUID workspaceId, String message,
                             Instant expiresAt, UUID invitedBy) {
        if (email == null || email.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Eine Einladung braucht eine Adresse");
        }
        String normalized = email.strip().toLowerCase(java.util.Locale.ROOT);

        if (isAlreadyMember(tenantId, normalized)) {
            throw ApiException.conflict("Diese Adresse gehört bereits zu diesem Konto");
        }
        Integer pending = jdbc.queryForObject("""
                select count(*) from invitations
                where tenant_id = ? and email = ? and status = 'PENDING'
                """, Integer.class, tenantId, normalized);
        if (pending != null && pending > 0) {
            throw ApiException.duplicate("Für diese Adresse liegt bereits eine offene Einladung vor");
        }

        String token = newToken();
        Instant expiry = expiresAt == null
                ? Instant.now().plusSeconds(DEFAULT_INVITATION_DAYS * 24 * 3600) : expiresAt;

        UUID invitationId = jdbc.queryForObject("""
                insert into invitations (id, tenant_id, workspace_id, email, role_id, invited_by,
                                         token_hash, status, message, expires_at)
                values (gen_random_uuid(), ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                returning id
                """, UUID.class, tenantId, workspaceId, normalized, roleId, invitedBy, hash(token),
                message, java.sql.Timestamp.from(expiry));

        recordChange(tenantId, null, invitedBy, "INVITED", null,
                "{\"email\":\"" + normalized + "\"}");
        log.info("Invitation {} created for tenant {} by {}", invitationId, tenantId, invitedBy);
        return new Invitation(invitationId, normalized, token, expiry);
    }

    /**
     * Accepts an invitation.
     *
     * <p>The token is looked up by hash, and the invitation must still be pending and unexpired. An
     * expired invitation is marked as such rather than left looking open.</p>
     */
    @Transactional
    public UUID accept(String token, UUID userId) {
        Map<String, Object> invitation = requireByToken(token);
        if (!"PENDING".equals(String.valueOf(invitation.get("status")))) {
            throw ApiException.conflict("Diese Einladung ist nicht mehr offen");
        }
        Instant expiresAt = ((java.sql.Timestamp) invitation.get("expires_at")).toInstant();
        if (expiresAt.isBefore(Instant.now())) {
            // In its own transaction: this method then throws, and a marker written here would roll back
            // with it, leaving an expired invitation looking open. The record has to outlive the refusal.
            markExpired((UUID) invitation.get("id"));
            throw ApiException.conflict("Diese Einladung ist abgelaufen");
        }

        UUID tenantId = (UUID) invitation.get("tenant_id");
        UUID workspaceId = (UUID) invitation.get("workspace_id");
        UUID roleId = (UUID) invitation.get("role_id");

        // A membership that already exists is reused: accepting an invitation for an address that is
        // already a member of another workspace should not create a second membership row.
        UUID membershipId = existingMembership(tenantId, userId);
        if (membershipId == null) {
            membershipId = jdbc.queryForObject("""
                    insert into memberships (id, tenant_id, workspace_id, user_id, status, is_owner,
                                             invited_by, joined_at)
                    values (gen_random_uuid(), ?, ?, ?, 'ACTIVE', false, ?, now())
                    returning id
                    """, UUID.class, tenantId, workspaceId, userId, invitation.get("invited_by"));
        }
        else {
            jdbc.update("update memberships set status = 'ACTIVE' where id = ?", membershipId);
        }
        if (roleId != null) {
            jdbc.update("""
                    insert into membership_roles (membership_id, role_id, granted_at)
                    values (?, ?, now()) on conflict do nothing
                    """, membershipId, roleId);
        }

        jdbc.update("""
                update invitations set status = 'ACCEPTED', accepted_at = now(), accepted_by = ?
                where id = ?
                """, userId, invitation.get("id"));

        recordChange(tenantId, userId, userId, "INVITATION_ACCEPTED", null, "{}");
        log.info("Invitation {} accepted by {}", invitation.get("id"), userId);
        return membershipId;
    }

    /**
     * Marks an invitation expired, in a transaction of its own.
     *
     * <p>Programmatic rather than {@code @Transactional(REQUIRES_NEW)}: this is called from inside
     * {@link #accept}, a self-invocation that does not pass through the transaction proxy, so the
     * annotation would have been silently ignored and the marker would have rolled back with the refusal
     * it is supposed to outlive.</p>
     */
    public void markExpired(UUID invitationId) {
        var template = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        template.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.execute(status -> jdbc.update(
                        "update invitations set status = 'EXPIRED' where id = ? and status = 'PENDING'",
                        invitationId));
    }

    /** Withdraws an invitation. The token stops working at once, because the status is checked. */
    @Transactional
    public void revokeInvitation(UUID tenantId, UUID invitationId, UUID revokedBy) {
        int updated = jdbc.update("""
                update invitations set status = 'REVOKED', revoked_at = now()
                where id = ? and tenant_id = ? and status = 'PENDING'
                """, invitationId, tenantId);
        if (updated == 0) {
            throw ApiException.conflict("Diese Einladung ist nicht mehr offen");
        }
        recordChange(tenantId, null, revokedBy, "INVITATION_REVOKED", null,
                "{\"invitationId\":\"" + invitationId + "\"}");
    }

    /** The open invitations of a tenant, without any token. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> pendingInvitations(UUID tenantId) {
        return jdbc.queryForList("""
                select id, email, role_id, workspace_id, invited_by, expires_at, created_at
                from invitations where tenant_id = ? and status = 'PENDING' order by created_at
                """, tenantId);
    }

    /** Marks the invitations whose time has passed, for a caller that reports on them. */
    @Transactional
    public int expireOverdueInvitations(UUID tenantId) {
        return jdbc.update("""
                update invitations set status = 'EXPIRED'
                where tenant_id = ? and status = 'PENDING' and expires_at < now()
                """, tenantId);
    }

    // ---------------------------------------------------------------- temporary case access

    /**
     * Grants a member access to a case, optionally until a date.
     *
     * <p>{@code expiresAt} is what makes the access temporary, and it is enforced when the access is
     * asked for rather than by a job that has to run. Access that depends on somebody remembering to
     * remove it is access that outlives its reason.</p>
     */
    @Transactional
    public UUID grantCaseAccess(UUID tenantId, UUID caseId, UUID userId, String role,
                                String accessLevel, Instant expiresAt, UUID grantedBy) {
        if (userId == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Zugriff wird einer Person gewährt");
        }
        String normalizedRole = role == null ? "PARTICIPANT"
                : role.toUpperCase(java.util.Locale.ROOT);
        if (!PARTICIPANT_ROLES.contains(normalizedRole)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Unbekannte Rolle: " + role);
        }
        String normalizedLevel = accessLevel == null ? "READ"
                : accessLevel.toUpperCase(java.util.Locale.ROOT);
        if (!ACCESS_LEVELS.contains(normalizedLevel)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Unbekannte Zugriffsstufe: " + accessLevel);
        }
        requireCase(tenantId, caseId);
        if (!isMember(tenantId, userId)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Zugriff auf ein Verfahren setzt eine Mitgliedschaft voraus");
        }

        UUID participantId = jdbc.queryForObject("""
                insert into case_participants (id, case_id, tenant_id, user_id, role, access_level,
                                              granted_by, granted_at, expires_at)
                values (gen_random_uuid(), ?, ?, ?, ?, ?, ?, now(), ?)
                on conflict (case_id, user_id, role) where user_id is not null and revoked_at is null
                do update set access_level = excluded.access_level, granted_by = excluded.granted_by,
                              granted_at = now(), expires_at = excluded.expires_at, revoked_at = null
                returning id
                """, UUID.class, caseId, tenantId, userId, normalizedRole, normalizedLevel, grantedBy,
                expiresAt == null ? null : java.sql.Timestamp.from(expiresAt));

        recordChange(tenantId, userId, grantedBy,
                expiresAt == null ? "ROLE_GRANTED" : "TEMPORARY_ACCESS_SET", normalizedRole,
                "{\"caseId\":\"" + caseId + "\",\"accessLevel\":\"" + normalizedLevel + "\"}");
        log.info("Case access granted on {} to {} ({} until {})", caseId, userId, normalizedLevel,
                expiresAt);
        return participantId;
    }

    /** Takes a case access away. */
    @Transactional
    public void revokeCaseAccess(UUID tenantId, UUID caseId, UUID userId, UUID revokedBy) {
        int updated = jdbc.update("""
                update case_participants set revoked_at = now()
                where case_id = ? and tenant_id = ? and user_id = ? and revoked_at is null
                """, caseId, tenantId, userId);
        if (updated == 0) {
            throw ApiException.notFound("case access");
        }
        recordChange(tenantId, userId, revokedBy, "ACCESS_REVOKED", null,
                "{\"caseId\":\"" + caseId + "\"}");
    }

    /**
     * What a member may do with a case right now.
     *
     * <p>Returns nothing when the grant has expired or been withdrawn, which is the whole point of a
     * temporary right.</p>
     */
    @Transactional(readOnly = true)
    public List<CaseAccess> caseAccess(UUID tenantId, UUID userId, UUID caseId) {
        return jdbc.query("""
                select role, access_level, expires_at from case_participants
                where case_id = ? and tenant_id = ? and user_id = ? and revoked_at is null
                  and (expires_at is null or expires_at > now())
                order by granted_at
                """, (rs, row) -> new CaseAccess(rs.getString("role"), rs.getString("access_level"),
                rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant(),
                true), caseId, tenantId, userId);
    }

    /** Whether a member may read a case, which is the question every case endpoint asks first. */
    @Transactional(readOnly = true)
    public boolean mayReadCase(UUID tenantId, UUID userId, UUID caseId) {
        return !caseAccess(tenantId, userId, caseId).isEmpty();
    }

    /** Suspend a member without removing what they were part of. */
    @Transactional
    public void suspendMember(UUID tenantId, UUID userId, UUID suspendedBy) {
        int updated = jdbc.update("""
                update memberships set status = 'SUSPENDED' where tenant_id = ? and user_id = ?
                  and status = 'ACTIVE'
                """, tenantId, userId);
        if (updated == 0) {
            throw ApiException.notFound("membership");
        }
        recordChange(tenantId, userId, suspendedBy, "MEMBER_SUSPENDED", null, "{}");
        log.info("Member {} suspended in tenant {}", userId, tenantId);
    }

    /** The access history of a person, which is what answers "how did they get to see this". */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> history(UUID tenantId, UUID subjectUserId) {
        return jdbc.queryForList("""
                select change_type, role_code, details, actor_user_id, created_at
                from access_change_log
                where tenant_id = ? and (subject_user_id = ? or subject_user_id is null)
                order by created_at
                """, tenantId, subjectUserId);
    }

    // ---------------------------------------------------------------- internals

    private Map<String, Object> requireByToken(String token) {
        if (token == null || token.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Kein Einladungstoken übergeben");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, tenant_id, workspace_id, role_id, status, expires_at, invited_by
                from invitations where token_hash = ?
                """, hash(token));
        if (rows.isEmpty()) {
            throw ApiException.notFound("invitation");
        }
        return rows.getFirst();
    }

    private UUID existingMembership(UUID tenantId, UUID userId) {
        List<UUID> found = jdbc.query("""
                select id from memberships where tenant_id = ? and user_id = ?
                """, (rs, row) -> rs.getObject("id", UUID.class), tenantId, userId);
        return found.isEmpty() ? null : found.getFirst();
    }

    private boolean isMember(UUID tenantId, UUID userId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from memberships where tenant_id = ? and user_id = ? and status = 'ACTIVE'
                """, Integer.class, tenantId, userId);
        return count != null && count > 0;
    }

    private boolean isAlreadyMember(UUID tenantId, String email) {
        Integer count = jdbc.queryForObject("""
                select count(*) from memberships m join users u on u.id = m.user_id
                where m.tenant_id = ? and lower(u.email) = ?
                """, Integer.class, tenantId, email);
        return count != null && count > 0;
    }

    private void requireCase(UUID tenantId, UUID caseId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from cases where id = ? and tenant_id = ? and deleted_at is null
                """, Integer.class, caseId, tenantId);
        if (count == null || count == 0) {
            throw ApiException.notFound("case");
        }
    }

    /** Access changes are recorded; that is the answer to "how did this person get to see this". */
    private void recordChange(UUID tenantId, UUID subjectUserId, UUID actorUserId, String changeType,
                              String roleCode, String details) {
        jdbc.update("""
                insert into access_change_log (id, tenant_id, subject_user_id, actor_user_id, change_type,
                                               role_code, details)
                values (gen_random_uuid(), ?, ?, ?, ?, ?, ?::jsonb)
                """, tenantId, subjectUserId, actorUserId, changeType, roleCode, details);
    }

    /** A token that is shown once and stored hashed. */
    static String newToken() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return "bcinv_" + HexFormat.of().formatHex(bytes);
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (Exception ex) {
            throw new IllegalStateException("SHA-256 ist nicht verfügbar", ex);
        }
    }

    /** A date as the access rules use it, for a caller that wants to grant until the end of a day. */
    public static Instant endOfDay(LocalDate date) {
        return date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusSeconds(1);
    }
}
