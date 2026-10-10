package de.buerokratiecopilot.support;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.identity.MembershipRepository;
import de.buerokratiecopilot.identity.MembershipRoleRepository;
import de.buerokratiecopilot.identity.RoleRepository;
import de.buerokratiecopilot.identity.TenantRepository;
import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.domain.Membership;
import de.buerokratiecopilot.identity.domain.MembershipRole;
import de.buerokratiecopilot.identity.domain.Role;
import de.buerokratiecopilot.identity.domain.Tenant;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * Creates real tenants, users and memberships in the test database.
 *
 * <p>Tests must never fake the data model: the audit trail, row level security and the RBAC
 * tables all reference real rows, so fixtures insert real rows through the same repositories
 * the application uses.</p>
 */
@Component
public class TestDataFactory {

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final MembershipRepository membershipRepository;
    private final MembershipRoleRepository membershipRoleRepository;
    private final RoleRepository roleRepository;

    public TestDataFactory(TenantRepository tenantRepository, UserRepository userRepository,
                           MembershipRepository membershipRepository,
                           MembershipRoleRepository membershipRoleRepository, RoleRepository roleRepository) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.membershipRoleRepository = membershipRoleRepository;
        this.roleRepository = roleRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Tenant createTenant(String accountType) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Tenant tenant = new Tenant(UUID.randomUUID(), "test-" + suffix, "Test Tenant " + suffix, accountType);
        return tenantRepository.save(tenant);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Tenant createCompany() {
        return createTenant("COMPANY");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public User createUser(String email) {
        User user = new User(UUID.randomUUID(), email, "Test User");
        user.setStatus(User.Status.ACTIVE);
        user.markEmailVerified(Instant.now());
        return userRepository.save(user);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Membership createMembership(Tenant tenant, User user, String... roleCodes) {
        Membership membership = new Membership(UUID.randomUUID(), tenant.getId(), user.getId(),
                Membership.Status.ACTIVE);
        membership.setJoinedAt(Instant.now());
        membership = membershipRepository.save(membership);

        for (String roleCode : roleCodes) {
            Role role = roleRepository.findByCodeAndTenantIdIsNull(roleCode)
                    .orElseThrow(() -> new IllegalStateException("System role not seeded: " + roleCode));
            membershipRoleRepository.save(new MembershipRole(membership.getId(), role.getId(), null, null));
        }
        return membership;
    }

    /** Creates a tenant with an owner and returns the tenant: the most common test setup. */
    public Tenant createTenantWithOwner(String email) {
        Tenant tenant = createCompany();
        User owner = createUser(email);
        createMembership(tenant, owner, Role.OWNER);
        return tenant;
    }

    public User ownerOf(Tenant tenant) {
        return membershipRepository.findActiveByTenant(tenant.getId()).stream()
                .findFirst()
                .map(m -> userRepository.findById(m.getUserId()).orElseThrow())
                .orElseThrow(() -> new IllegalStateException("Tenant has no active member"));
    }

    public List<String> permissionCodesOf(Membership membership) {
        return membershipRoleRepository.findPermissionCodes(membership.getId());
    }

    /** Runs an action with the tenant context set, as the request filter does. */
    public <T> T inTenant(UUID tenantId, java.util.function.Supplier<T> action) {
        return RequestContext.runAs(tenantId, action);
    }
}
