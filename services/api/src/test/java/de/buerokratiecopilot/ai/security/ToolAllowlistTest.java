package de.buerokratiecopilot.ai.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.ai.security.ToolPolicy.Request;
import de.buerokratiecopilot.ai.security.ToolPolicy.Risk;
import de.buerokratiecopilot.shared.error.ApiException;

/**
 * Verifies the tool allowlist and the permission check (spec §25, E-006).
 *
 * <p>A model acting for a user acts with that user's rights and no others, and a tool the tenant has not
 * enabled does not exist. The tests check both from the side that matters: not "the list contains the
 * right names" but "the call is refused".</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class ToolAllowlistTest {

    @Autowired
    private ToolPolicy policy;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID tenant() {
        return jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'Werkzeug Test', 'COMPANY') returning id
                """, UUID.class, "tool-" + UUID.randomUUID().toString().substring(0, 12));
    }

    /** A person with exactly one permission. */
    private UUID personWith(UUID tenantId, String permissionCode) {
        UUID userId = jdbc.queryForObject("""
                insert into users (id, email, status, display_name, locale, time_zone,
                                   failed_login_attempts, must_change_password)
                values (gen_random_uuid(), ?, 'ACTIVE', 'Sachbearbeiter', 'de', 'Europe/Berlin', 0, false)
                returning id
                """, UUID.class, "tw-" + UUID.randomUUID().toString().substring(0, 10) + "@example.de");

        UUID roleId = jdbc.queryForObject("""
                insert into roles (id, tenant_id, code, name, is_system)
                values (gen_random_uuid(), ?, ?, '{"de":"Testrolle"}'::jsonb, false) returning id
                """, UUID.class, tenantId, "ROLE_" + UUID.randomUUID().toString().substring(0, 8));

        jdbc.update("insert into role_permissions (role_id, permission_id) select ?, id from permissions "
                + "where code = ?", roleId, permissionCode);

        UUID membershipId = jdbc.queryForObject("""
                insert into memberships (id, tenant_id, user_id, status, is_owner)
                values (gen_random_uuid(), ?, ?, 'ACTIVE', false) returning id
                """, UUID.class, tenantId, userId);
        jdbc.update("insert into membership_roles (membership_id, role_id, granted_at) values (?, ?, now())",
                membershipId, roleId);
        return userId;
    }

    /** Runs a policy call in the tenant's context, which is what row level security requires. */
    private <T> T asTenant(UUID tenantId, java.util.function.Supplier<T> action) {
        return de.buerokratiecopilot.shared.context.RequestContext.runAs(tenantId, action);
    }

    private void enable(UUID tenantId, String toolName) {
        asTenant(tenantId, () -> {
            policy.setToolEnabled(tenantId, toolName, true, null);
            return null;
        });
    }

    private void disable(UUID tenantId, String toolName) {
        asTenant(tenantId, () -> {
            policy.setToolEnabled(tenantId, toolName, false, null);
            return null;
        });
    }

    private ToolPolicy.Decision decide(UUID tenantId, Request request) {
        return asTenant(tenantId, () -> policy.decide(tenantId, request));
    }

    @Test
    @DisplayName("a tenant that configured nothing gets the read-only tools only")
    void unconfiguredTenantGetsReadOnlyTools() {
        UUID tenantId = tenant();

        assertThat(asTenant(tenantId, () -> policy.isAllowed(tenantId, "read_document"))).isTrue();
        assertThat(asTenant(tenantId, () -> policy.isAllowed(tenantId, "search_documents"))).isTrue();
        assertThat(asTenant(tenantId, () -> policy.isAllowed(tenantId, "send_email")))
                .as("not configured is not the same as permitted")
                .isFalse();
        assertThat(asTenant(tenantId, () -> policy.isAllowed(tenantId, "export_data"))).isFalse();
        assertThat(asTenant(tenantId, () -> policy.allowedTools(tenantId))).allSatisfy(tool ->
                assertThat(tool.risk()).isEqualTo(Risk.READ_ONLY));
    }

    @Test
    @DisplayName("a tool that is not on the list does not exist for that tenant")
    void toolNotOnTheListIsRefused() {
        UUID tenantId = tenant();

        ToolPolicy.Decision decision = decide(tenantId, new Request("create_task", Map.of(),
                null, UntrustedContent.Origin.PLATFORM, null));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.code()).isEqualTo("NOT_ALLOWED");
    }

    @Test
    @DisplayName("a tenant can enable a tool, and then it is reachable")
    void enabledToolBecomesReachable() {
        UUID tenantId = tenant();
        enable(tenantId, "create_task");

        ToolPolicy.Decision decision = decide(tenantId, new Request("create_task",
                Map.of("title", "Aufgabe"), null, UntrustedContent.Origin.PLATFORM, null));

        // Allowed as far as the allowlist goes; a tool with an effect still needs a person.
        assertThat(decision.code()).isEqualTo("NEEDS_CONFIRMATION");
        assertThat(decision.needsConfirmation()).isTrue();
    }

    @Test
    @DisplayName("a tenant can withdraw a read-only tool")
    void readOnlyToolCanBeWithdrawn() {
        UUID tenantId = tenant();
        disable(tenantId, "summarize");

        assertThat(asTenant(tenantId, () -> policy.isAllowed(tenantId, "summarize"))).isFalse();
        assertThat(decide(tenantId, new Request("summarize", Map.of(), null,
                UntrustedContent.Origin.PLATFORM, null)).code()).isEqualTo("NOT_ALLOWED");
    }

    @Test
    @DisplayName("an allowlist of one tenant does not affect another")
    void allowlistIsPerTenant() {
        UUID first = tenant();
        UUID second = tenant();
        enable(first, "send_email");

        assertThat(asTenant(first, () -> policy.isAllowed(first, "send_email"))).isTrue();
        assertThat(asTenant(second, () -> policy.isAllowed(second, "send_email")))
                .as("one tenant enabling a tool must not enable it for everybody")
                .isFalse();
    }

    @Test
    @DisplayName("a tool with an effect needs a person even when it is enabled")
    void effectiveToolNeedsConfirmation() {
        UUID tenantId = tenant();
        enable(tenantId, "draft_letter");

        ToolPolicy.Decision decision = decide(tenantId, new Request("draft_letter", Map.of(),
                null, UntrustedContent.Origin.PLATFORM, null));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.needsConfirmation()).isTrue();
        assertThat(decision.code()).isEqualTo("NEEDS_CONFIRMATION");
    }

    @Test
    @DisplayName("a user without the required permission is refused")
    void missingPermissionIsRefused() {
        UUID tenantId = tenant();
        enable(tenantId, "create_task");
        UUID outsider = personWith(tenantId, "tasks.view");

        ToolPolicy.Decision decision = decide(tenantId, new Request("create_task", Map.of(),
                outsider, UntrustedContent.Origin.PLATFORM, null));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.code()).isEqualTo("MISSING_PERMISSION");
        assertThat(decision.reason()).contains("tasks.create");
    }

    @Test
    @DisplayName("a user with the permission passes the permission gate")
    void matchingPermissionPasses() {
        UUID tenantId = tenant();
        enable(tenantId, "create_task");
        UUID clerk = personWith(tenantId, "tasks.create");

        ToolPolicy.Decision decision = decide(tenantId, new Request("create_task", Map.of(),
                clerk, UntrustedContent.Origin.PLATFORM, null));

        // Past the permission check; the effect is what stops it now, not the rights.
        assertThat(decision.code()).isEqualTo("NEEDS_CONFIRMATION");
    }

    @Test
    @DisplayName("a parameter that is not an identifier is refused")
    void malformedIdentifierIsRefused() {
        UUID tenantId = tenant();

        ToolPolicy.Decision decision = decide(tenantId, new Request("read_document",
                Map.of("documentId", "'; DROP TABLE documents; --"), null,
                UntrustedContent.Origin.PLATFORM, null));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.code()).isEqualTo("INVALID_PARAMETERS");
    }

    @Test
    @DisplayName("an oversized parameter is refused")
    void oversizedParameterIsRefused() {
        UUID tenantId = tenant();

        ToolPolicy.Decision decision = decide(tenantId, new Request("read_document",
                Map.of("filter", "x".repeat(3000)), null, UntrustedContent.Origin.PLATFORM, null));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.code()).isEqualTo("INVALID_PARAMETERS");
    }

    @Test
    @DisplayName("an unknown tool cannot be enabled")
    void unknownToolCannotBeEnabled() {
        UUID tenantId = tenant();

        assertThatThrownBy(() -> asTenant(tenantId, () -> {
            policy.setToolEnabled(tenantId, "delete_everything", true, null);
            return null;
        }))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("the catalogue names the risk of every tool")
    void catalogueCarriesTheRisk() {
        var catalogue = policy.catalogue();

        assertThat(catalogue).isNotEmpty();
        assertThat(catalogue).allSatisfy(tool -> {
            assertThat(tool.risk()).isNotNull();
            assertThat(tool.requiredPermission()).isNotBlank();
            assertThat(tool.description()).isNotBlank();
        });
        assertThat(catalogue).anySatisfy(tool -> assertThat(tool.risk()).isEqualTo(Risk.DANGEROUS));
        assertThat(catalogue).anySatisfy(tool -> assertThat(tool.risk()).isEqualTo(Risk.READ_ONLY));
    }

    @Test
    @DisplayName("the decision carries the reason a caller can show")
    void decisionCarriesItsReason() {
        UUID tenantId = tenant();

        var decision = decide(tenantId, new Request("send_email", Map.of(), null,
                UntrustedContent.Origin.PLATFORM, null));

        assertThat(decision.asMap()).containsEntry("allowed", false)
                .containsEntry("code", "NOT_ALLOWED");
        assertThat(decision.reason()).isNotBlank();
    }
}
