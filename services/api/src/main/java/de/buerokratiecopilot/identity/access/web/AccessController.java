package de.buerokratiecopilot.identity.access.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.identity.access.InvitationService;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Who may join, who may stay, and who may see which case (spec §4, §20).
 *
 * <p>{@code InvitationService} was implemented and tested with no endpoint, so nobody could be invited to
 * anything. This is the way in.</p>
 *
 * <p><strong>The permissions named here are ones that exist.</strong> {@code taxonomy.manage} was once
 * required by an endpoint while the permission was in no catalogue at all and granted to no role, which
 * made every call answer 403 — and a service test did not notice, because it created the permission itself.
 * The names below were checked against the catalogue first: there is no {@code users.invite}, and inventing
 * one would have repeated exactly that mistake. Inviting somebody is administering the account, and the
 * permission for that is {@code settings.edit}.</p>
 *
 * <p><strong>The actor comes from the session.</strong> An invitation names who sent it, and a body that
 * could name somebody else would let one person's name stand on another's invitation.</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Access", description = "Invitations, members and case access")
public class AccessController {

    private static final String ADMINISTER = "hasAuthority('settings.edit')";
    private static final String SEE = "hasAnyAuthority('settings.view','settings.edit')";

    private final InvitationService access;

    public AccessController(InvitationService access) {
        this.access = access;
    }

    /** What a caller sends to invite somebody. */
    /** {@code roleCode} (OWNER, ADMIN, ...) is the readable alternative to {@code roleId}. */
    public record InviteRequest(String email, UUID roleId, UUID workspaceId, String message,
                                Instant expiresAt, String roleCode) {
    }

    public record RoleRequest(String roleCode) {
    }

    /** What a caller sends to let somebody see a case. */
    public record CaseAccessRequest(UUID userId, String role, String accessLevel, Instant expiresAt) {
    }

    /** What a caller sends to take access away. */
    public record RevokeRequest(UUID userId) {
    }

    /** The account's members, so an interface can name people rather than identifiers. */
    @GetMapping("/members")
    @PreAuthorize(SEE)
    @Operation(summary = "The people who belong to this account")
    public List<Map<String, Object>> members() {
        return access.members(tenant());
    }

    /** An invitation, with the token that exists only in this answer. */
    @PostMapping("/invitations")
    @PreAuthorize(ADMINISTER)
    @Operation(summary = "Invites somebody; the link is shown once and stored only as its hash")
    public ResponseEntity<Map<String, Object>> invite(@RequestBody InviteRequest request) {
        UUID tenantId = tenant();
        UUID actor = CurrentUser.required();
        UUID roleId = request.roleId() != null ? request.roleId() : access.systemRoleId(request.roleCode());
        if (request.roleId() == null && request.roleCode() != null && roleId == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Unbekannte Rolle: " + request.roleCode());
        }
        InvitationService.Invitation invitation = access.invite(tenantId, request.email(),
                roleId, request.workspaceId(), request.message(), request.expiresAt(), actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(invitation.asMap());
    }

    /** The invitations nobody has accepted yet, without their tokens. */
    @GetMapping("/invitations")
    @PreAuthorize(ADMINISTER)
    @Operation(summary = "The invitations that are still open")
    public List<Map<String, Object>> pendingInvitations() {
        return access.pendingInvitations(tenant());
    }

    /** Withdraws an invitation, which stops working at once because the status is checked. */
    @PostMapping("/invitations/{invitationId}/revoke")
    @PreAuthorize(ADMINISTER)
    @Operation(summary = "Withdraws an invitation")
    public ResponseEntity<Void> revokeInvitation(@PathVariable UUID invitationId) {
        access.revokeInvitation(tenant(), invitationId, CurrentUser.required());
        return ResponseEntity.noContent().build();
    }

    /** Marks the invitations whose time has passed, for an administrator who reports on them. */
    @PostMapping("/invitations/expire-overdue")
    @PreAuthorize(ADMINISTER)
    @Operation(summary = "Closes the invitations whose time has passed")
    public Map<String, Object> expireOverdue() {
        int expired = access.expireOverdueInvitations(tenant());
        return Map.of("expired", expired);
    }

    /**
     * Accepts an invitation.
     *
     * <p>The token is the credential; the session says who is joining. Both are needed: a token alone would
     * let anybody who found the link join as themselves, and a session alone would let somebody join an
     * account they were never invited to.</p>
     */
    @PostMapping("/invitations/accept")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Accepts an invitation with its token")
    public Map<String, Object> accept(@RequestBody Map<String, String> request) {
        String token = request == null ? null : request.get("token");
        UUID membershipId = access.accept(token, CurrentUser.required());
        return Map.of("membershipId", membershipId.toString());
    }

    /**
     * Suspends a member.
     *
     * <p>Nothing is deleted: what somebody was part of stays, and the record of their having been part of it
     * stays with it.</p>
     */
    @PostMapping("/members/{userId}/role")
    @PreAuthorize(ADMINISTER)
    @Operation(summary = "Gives a member another role; the last owner cannot be demoted")
    public ResponseEntity<Void> changeRole(@PathVariable UUID userId, @RequestBody RoleRequest request) {
        access.changeMemberRole(tenant(), userId, request == null ? null : request.roleCode(),
                CurrentUser.required());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/members/{userId}/suspend")
    @PreAuthorize(ADMINISTER)
    @Operation(summary = "Suspends a member without removing what they were part of")
    public ResponseEntity<Void> suspend(@PathVariable UUID userId) {
        UUID tenantId = tenant();
        access.suspendMember(tenantId, userId, CurrentUser.required());
        return ResponseEntity.noContent().build();
    }

    /** Lets a member see one case, optionally until a date. */
    @PostMapping("/cases/{caseId}/access")
    @PreAuthorize(ADMINISTER)
    @Operation(summary = "Grants access to one case; without an end date it is not temporary")
    public ResponseEntity<Map<String, Object>> grantCaseAccess(@PathVariable UUID caseId,
                                                              @RequestBody CaseAccessRequest request) {
        UUID tenantId = tenant();
        if (request.userId() == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Zugriff wird einer Person gewährt");
        }
        // The service refuses an unknown role or access level and a case of another tenant.
        UUID participantId = access.grantCaseAccess(tenantId, caseId, request.userId(), request.role(),
                request.accessLevel(), request.expiresAt(), CurrentUser.required());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("participantId", participantId.toString()));
    }

    /** Takes a case access away. */
    @PostMapping("/cases/{caseId}/access/revoke")
    @PreAuthorize(ADMINISTER)
    @Operation(summary = "Takes access to a case away")
    public ResponseEntity<Void> revokeCaseAccess(@PathVariable UUID caseId,
                                                 @RequestBody RevokeRequest request) {
        UUID tenantId = tenant();
        access.revokeCaseAccess(tenantId, caseId, request.userId(), CurrentUser.required());
        return ResponseEntity.noContent().build();
    }

    /**
     * Whether a member may read a case right now.
     *
     * <p>Answered by the same code that grants it, so a screen and an endpoint cannot disagree about what
     * somebody may see.</p>
     */
    @GetMapping("/cases/{caseId}/access/{userId}")
    @PreAuthorize(SEE)
    @Operation(summary = "What a member may do with a case right now")
    public Map<String, Object> caseAccess(@PathVariable UUID caseId, @PathVariable UUID userId) {
        UUID tenantId = tenant();
        var grants = access.caseAccess(tenantId, userId, caseId);
        return Map.of("mayRead", !grants.isEmpty(),
                "grants", grants.stream().map(grant -> grant.asMap()).toList());
    }

    /**
     * How somebody came to have access.
     *
     * <p>This is the question asked when something has gone wrong, which is why every change of access is
     * recorded rather than left to be reconstructed.</p>
     */
    @GetMapping("/members/{userId}/access-history")
    @PreAuthorize(SEE)
    @Operation(summary = "How this person came to have access")
    public List<Map<String, Object>> accessHistory(@PathVariable UUID userId) {
        return access.history(tenant(), userId);
    }

    /** The tenant the caller is acting in, which is never taken from a body. */
    private UUID tenant() {
        return RequestContext.tenantId().orElseThrow();
    }
}
