package de.buerokratiecopilot.cases;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * Builds a second user inside an existing tenant for the access tests.
 *
 * <p>Registration creates a new tenant per account, and the invitation flow (task A-017) is not
 * implemented yet, so the tests create the colleague directly. Coping the membership and its roles
 * from the first user is deliberate: the point of these tests is case access, not role assignment, and
 * giving both users the same permissions isolates the rule under test.</p>
 */
@Component
public class CaseTestSupport {

    private final JdbcTemplate jdbc;

    public CaseTestSupport(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A colleague with the ordinary employee role, in the same tenant.
     *
     * <p>Copying the founder's roles would make the colleague an owner, and an owner holds
     * {@code cases.assign} - the permission that allows managing every case in the tenant. The rule
     * under test is access for an ordinary colleague, so the role has to be an ordinary one.</p>
     */
    public UUID colleagueIn(UUID tenantId, UUID templateUserId, String email) {
        return RequestContext.runAs(tenantId, () -> {
            UUID userId = jdbc.queryForObject("""
                    insert into users (id, email, status, display_name, locale, time_zone,
                                       failed_login_attempts, must_change_password, email_verified_at,
                                       password_hash)
                    select gen_random_uuid(), ?, 'ACTIVE', 'Kollege', locale, time_zone, 0, false, now(),
                           password_hash
                    from users where id = ?
                    returning id
                    """, UUID.class, email, templateUserId);

            UUID membershipId = jdbc.queryForObject("""
                    insert into memberships (id, tenant_id, user_id, status, is_owner)
                    values (gen_random_uuid(), ?, ?, 'ACTIVE', false)
                    returning id
                    """, UUID.class, tenantId, userId);

            jdbc.update("""
                    insert into membership_roles (membership_id, role_id, granted_at)
                    select ?, id, now() from roles where tenant_id is null and code = 'EMPLOYEE'
                    """, membershipId);

            return userId;
        });
    }

    /** A contact of the tenant, for the external participants a case often has. */
    public UUID contactIn(UUID tenantId, String name) {
        return RequestContext.runAs(tenantId, () -> jdbc.queryForObject("""
                insert into contacts (id, tenant_id, contact_type, display_name, organization_name,
                                      is_customer, is_supplier)
                values (gen_random_uuid(), ?, 'ORGANIZATION', ?, ?, false, false)
                returning id
                """, UUID.class, tenantId, name, name));
    }

    /** Links a document to a case, the way the matching stage does. */
    public void linkDocument(UUID tenantId, UUID caseId, UUID documentId, String origin, double confidence) {
        RequestContext.runAs(tenantId, () -> jdbc.update("""
                insert into case_documents (id, tenant_id, case_id, document_id, link_origin, confidence,
                                            confirmed_at)
                values (gen_random_uuid(), ?, ?, ?, ?, ?, now())
                """, tenantId, caseId, documentId, origin, confidence));
    }
}
