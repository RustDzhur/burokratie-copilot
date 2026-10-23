package de.buerokratiecopilot.support;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.identity.domain.Role;
import de.buerokratiecopilot.identity.domain.Tenant;
import de.buerokratiecopilot.identity.domain.User;

/**
 * Creates a cabinet with an owner, a default workspace and an organisation - the structure a registration
 * produces.
 *
 * <p>Tests that need a tenant with somebody who may act in it used to get this from the account-setup flow
 * that the Kanzlei edition removed (§4.3). The flow is gone; its result is what those tests depend on, so it
 * is built here from the real repositories, with the real OWNER role. Nothing about the product is faked:
 * the rows are the rows a registration writes.</p>
 */
@Component
public class TestAccounts {

    private final JdbcTemplate jdbc;
    private final TestDataFactory data;

    public TestAccounts(JdbcTemplate jdbc, TestDataFactory data) {
        this.jdbc = jdbc;
        this.data = data;
    }

    /** What a test needs to know about the cabinet it just created. */
    public record Created(UUID tenantId, UUID workspaceId, UUID organizationId, UUID adminUserId) {
    }

    /** Builds one cabinet; {@code prefix} only makes the owner's address readable in a failure message. */
    public Created create(String prefix) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Tenant tenant = data.createCompany();
        User owner = data.createUser(prefix + "-" + suffix + "@example.de");
        data.createMembership(tenant, owner, Role.OWNER);

        UUID organizationId = jdbc.queryForObject("""
                insert into organizations (id, tenant_id, name, country_code, is_own_entity)
                values (gen_random_uuid(), ?, ?, 'DE', true)
                returning id
                """, UUID.class, tenant.getId(), tenant.getName());
        UUID workspaceId = jdbc.queryForObject("""
                insert into workspaces (id, tenant_id, organization_id, name, mode, is_default, settings)
                values (gen_random_uuid(), ?, ?, ?, 'BUSINESS', true, '{}'::jsonb)
                returning id
                """, UUID.class, tenant.getId(), organizationId, tenant.getName());

        // Registration marks the first person as the owner of the cabinet and puts them into its default
        // workspace. The orchestrator looks for exactly that owner before it turns a document into work
        // ("Der Mandant hat keinen aktiven Besitzer"), so a fixture without it is not a registered cabinet.
        jdbc.update("""
                update memberships set is_owner = true, workspace_id = ?
                where tenant_id = ? and user_id = ?
                """, workspaceId, tenant.getId(), owner.getId());

        // Registration writes the first audit event; some tests start at the account itself.
        jdbc.update("""
                insert into audit_events (id, tenant_id, actor_user_id, actor_type, category, action,
                                          entity_type, entity_id, description, changes, result, severity)
                values (gen_random_uuid(), ?, ?, 'USER', 'ADMIN', 'ACCOUNT_READY', 'tenant', ?,
                        'Test cabinet created', '{}'::jsonb, 'SUCCESS', 'NOTICE')
                """, tenant.getId(), owner.getId(), tenant.getId());

        return new Created(tenant.getId(), workspaceId, organizationId, owner.getId());
    }
}
