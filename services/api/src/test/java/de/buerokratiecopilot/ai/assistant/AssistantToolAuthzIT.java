package de.buerokratiecopilot.ai.assistant;

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

import de.buerokratiecopilot.ai.security.UntrustedContent;
import de.buerokratiecopilot.ai.security.ToolPolicy;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;

/**
 * Verifies that the assistant stays inside one tenant and one person's rights (spec §25, E-007 acceptance).
 *
 * <p>Two criteria. The assistant must not see another tenant's data, and sending, paying or terminating
 * must require an explicit confirmation. The first is enforced by row level security rather than by this
 * service's good behaviour, which is why the test tries to obtain another tenant's document through the
 * assistant and expects to be told only that it was not found.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class AssistantToolAuthzIT {

    @Autowired
    private AssistantService assistant;

    @Autowired
    private ToolPolicy policy;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID tenant() {
        return jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'Assistent Test', 'COMPANY') returning id
                """, UUID.class, "ast-" + UUID.randomUUID().toString().substring(0, 12));
    }

    /** A person with exactly the permissions given. */
    private UUID person(UUID tenantId, String... permissionCodes) {
        UUID userId = jdbc.queryForObject("""
                insert into users (id, email, status, display_name, locale, time_zone,
                                   failed_login_attempts, must_change_password)
                values (gen_random_uuid(), ?, 'ACTIVE', 'Sachbearbeiter', 'de', 'Europe/Berlin', 0, false)
                returning id
                """, UUID.class, "as-" + UUID.randomUUID().toString().substring(0, 10) + "@example.de");

        UUID roleId = jdbc.queryForObject("""
                insert into roles (id, tenant_id, code, name, is_system)
                values (gen_random_uuid(), ?, ?, '{"de":"Testrolle"}'::jsonb, false) returning id
                """, UUID.class, tenantId, "ROLE_" + UUID.randomUUID().toString().substring(0, 8));
        for (String permission : permissionCodes) {
            jdbc.update("insert into role_permissions (role_id, permission_id) select ?, id from "
                    + "permissions where code = ?", roleId, permission);
        }

        UUID membershipId = jdbc.queryForObject("""
                insert into memberships (id, tenant_id, user_id, status, is_owner)
                values (gen_random_uuid(), ?, ?, 'ACTIVE', false) returning id
                """, UUID.class, tenantId, userId);
        jdbc.update("insert into membership_roles (membership_id, role_id, granted_at) values (?, ?, now())",
                membershipId, roleId);
        return userId;
    }

    private java.util.UUID document(UUID tenantId, String name) {
        String hash = "%064x".formatted(java.util.concurrent.ThreadLocalRandom.current().nextLong());
        return jdbc.queryForObject("""
                insert into documents (id, tenant_id, original_filename, internal_name, mime_type,
                                       size_bytes, content_hash, source, processing_status, review_status)
                values (gen_random_uuid(), ?, ?, ?, 'application/pdf', 100, ?, 'UPLOAD', 'READY',
                        'NOT_REQUIRED')
                returning id
                """, java.util.UUID.class, tenantId, name, name, hash);
    }

    private AssistantService.Turn turn(UUID tenantId, UUID userId) {
        return new AssistantService.Turn(tenantId, userId, "Was ist offen?", "modell-1", "v1", "hash-1",
                UntrustedContent.Origin.PLATFORM, null);
    }

    private AssistantService.Answer ask(UUID tenantId, AssistantService.Turn turn, String tool,
                                        Map<String, Object> parameters) {
        return RequestContext.runAs(tenantId, () -> assistant.ask(turn, tool, parameters));
    }

    @Test
    @DisplayName("the assistant does not see another tenant's document")
    void anotherTenantsDocumentIsNotVisible() {
        UUID owner = tenant();
        UUID stranger = tenant();
        UUID ownerUser = person(owner, "documents.view");
        UUID strangerUser = person(stranger, "documents.view");
        UUID documentId = document(owner, "geheim.pdf");

        AssistantService.Answer answer = ask(stranger, turn(stranger, strangerUser), "read_document",
                Map.of("documentId", documentId.toString()));

        assertThat(answer.kind()).isEqualTo("ANSWER");
        assertThat(String.valueOf(answer.result()))
                .as("an assistant that says 'that belongs to somebody else' tells its user what another "
                        + "tenant has")
                .contains("nicht gefunden")
                .doesNotContain("geheim.pdf");
    }

    @Test
    @DisplayName("a tenant's own document is visible")
    void ownDocumentIsVisible() {
        UUID tenantId = tenant();
        UUID user = person(tenantId, "documents.view");
        UUID documentId = document(tenantId, "eigenes.pdf");

        AssistantService.Answer answer = ask(tenantId, turn(tenantId, user), "read_document",
                Map.of("documentId", documentId.toString()));

        assertThat(String.valueOf(answer.result())).contains("eigenes.pdf");
    }

    @Test
    @DisplayName("a user without the permission for a tool is refused")
    void missingPermissionIsRefused() {
        UUID tenantId = tenant();
        UUID user = person(tenantId, "tasks.view");

        AssistantService.Answer answer = ask(tenantId, turn(tenantId, user), "read_document",
                Map.of("documentId", UUID.randomUUID().toString()));

        assertThat(answer.kind()).isEqualTo("REFUSED");
        assertThat(answer.refusalCode()).isEqualTo("MISSING_PERMISSION");
    }

    @Test
    @DisplayName("a user with the permission may read")
    void matchingPermissionReads() {
        UUID tenantId = tenant();
        UUID user = person(tenantId, "documents.view");

        AssistantService.Answer answer = ask(tenantId, turn(tenantId, user), "search_documents", Map.of());

        assertThat(answer.isAnswer()).isTrue();
        assertThat(answer.result()).containsKey("documents");
    }

    @Test
    @DisplayName("a turn without a person is refused")
    void aTurnNeedsAPerson() {
        UUID tenantId = tenant();

        assertThatThrownBy(() -> ask(tenantId,
                new AssistantService.Turn(tenantId, null, "Frage", "m", "v1", "h",
                        UntrustedContent.Origin.PLATFORM, null),
                "search_documents", Map.of()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Person");
    }

    @Test
    @DisplayName("the assistant cannot be given a tenant to read from")
    void thereIsNoParameterForAnotherTenant() {
        UUID tenantId = tenant();
        UUID stranger = tenant();
        UUID user = person(tenantId, "documents.view");
        document(stranger, "fremd.pdf");

        // Even naming the other tenant explicitly changes nothing: the query is scoped, and row level
        // security would refuse anything else.
        AssistantService.Answer answer = ask(tenantId, turn(tenantId, user), "search_documents",
                Map.of("tenantId", stranger.toString()));

        assertThat(String.valueOf(answer.result())).doesNotContain("fremd.pdf");
    }

    @Test
    @DisplayName("the history names the model and the instruction version")
    void historyNamesTheModel() {
        UUID tenantId = tenant();
        UUID user = person(tenantId, "documents.view");
        ask(tenantId, turn(tenantId, user), "search_documents", Map.of());

        var history = RequestContext.runAs(tenantId, () -> assistant.history(tenantId, user, 10));

        assertThat(history).hasSize(1);
        assertThat(history.getFirst()).containsEntry("model", "modell-1")
                .containsEntry("instruction_version", "v1");
    }

    @Test
    @DisplayName("the history of one person does not show another's turns")
    void historyIsPerPerson() {
        UUID tenantId = tenant();
        UUID first = person(tenantId, "documents.view");
        UUID second = person(tenantId, "documents.view");
        ask(tenantId, turn(tenantId, first), "search_documents", Map.of());

        assertThat(RequestContext.runAs(tenantId, () -> assistant.history(tenantId, second, 10)))
                .isEmpty();
    }

    @Test
    @DisplayName("an assistant turn is recorded as a job of its own")
    void turnsAreRecorded() {
        UUID tenantId = tenant();
        UUID user = person(tenantId, "documents.view");
        ask(tenantId, turn(tenantId, user), "search_documents", Map.of());

        assertThat(jdbc.queryForObject("""
                select count(*) from ai_jobs where tenant_id = ? and job_type = 'CHAT'
                  and input ->> 'tool' = 'search_documents'
                """, Integer.class, tenantId)).isEqualTo(1);
    }

    @Test
    @DisplayName("a tool the tenant has withdrawn is not available to the assistant")
    void withdrawnToolIsNotAvailable() {
        UUID tenantId = tenant();
        UUID user = person(tenantId, "documents.view");
        RequestContext.runAs(tenantId, () -> {
            policy.setToolEnabled(tenantId, "search_documents", false, null);
            return null;
        });

        AssistantService.Answer answer = ask(tenantId, turn(tenantId, user), "search_documents", Map.of());

        assertThat(answer.kind()).isEqualTo("REFUSED");
        assertThat(answer.refusalCode()).isEqualTo("NOT_ALLOWED");
    }

    @Test
    @DisplayName("an invented tool is refused")
    void inventedToolIsRefused() {
        UUID tenantId = tenant();
        UUID user = person(tenantId, "documents.view");

        AssistantService.Answer answer = ask(tenantId, turn(tenantId, user), "read_everything", Map.of());

        assertThat(answer.refusalCode()).isEqualTo("UNKNOWN_TOOL");
    }
}
