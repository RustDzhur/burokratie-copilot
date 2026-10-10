package de.buerokratiecopilot.identity.security;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.identity.MembershipRepository;
import de.buerokratiecopilot.identity.MembershipRoleRepository;
import de.buerokratiecopilot.identity.domain.Membership;

/**
 * Resolves the effective permissions of a user inside a tenant.
 *
 * <p>Only the combination of an active membership, a non-expired role assignment and the
 * validity window of the membership yields permissions (spec §4). The result is a small list of
 * codes that Spring Security exposes as authorities, so a controller can declare
 * {@code @PreAuthorize("hasAuthority('documents.view')")}.</p>
 */
@Component
public class MembershipAuthorityResolver implements SessionAuthenticationFilter.MembershipAuthorityResolver {

    private static final Logger log = LoggerFactory.getLogger(MembershipAuthorityResolver.class);

    private final MembershipRepository memberships;
    private final MembershipRoleRepository membershipRoles;

    public MembershipAuthorityResolver(MembershipRepository memberships,
                                       MembershipRoleRepository membershipRoles) {
        this.memberships = memberships;
        this.membershipRoles = membershipRoles;
    }

    @Override
    public List<String> permissionsOf(UUID tenantId, UUID userId) {
        if (tenantId == null || userId == null) {
            return List.of();
        }
        var membership = memberships.findByTenantIdAndUserId(tenantId, userId)
                .filter(m -> m.getStatus() == Membership.Status.ACTIVE && m.isEffective(Instant.now()));
        if (membership.isEmpty()) {
            log.debug("No effective membership for user {} in tenant {}", userId, tenantId);
            return List.of();
        }
        return membershipRoles.findEffectivePermissionCodes(tenantId, userId, Instant.now());
    }
}
