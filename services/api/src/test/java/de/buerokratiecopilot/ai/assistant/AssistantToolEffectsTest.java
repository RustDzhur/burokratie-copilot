package de.buerokratiecopilot.ai.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.ai.security.ToolPolicy;
import de.buerokratiecopilot.ai.security.UntrustedContent;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.support.TenantContextExtension;

/**
 * The tools that operate the platform (spec §25): documents move between cases, deadlines and calendar
 * entries are recorded, cases change, documents are deleted softly — every one of them only after the
 * person confirmed the proposal, and never for another tenant's objects.
 */
@SpringBootTest
@ExtendWith(TenantContextExtension.class)
@ActiveProfiles("test")
class AssistantToolEffectsTest {

    @Autowired
    private AssistantService assistant;

    @Autowired
    private ToolPolicy policy;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID tenant() {
        return jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'Werkzeuge Test', 'COMPANY') returning id
                """, UUID.class, "fx-" + UUID.randomUUID().toString().substring(0, 12));
    }

    private UUID person(UUID tenantId, String... permissions) {
        return RequestContext.runAs(tenantId, () -> {
            UUID userId = jdbc.queryForObject("""
                    insert into users (id, email, status, display_name, locale, time_zone,
                                       failed_login_attempts, must_change_password)
                    values (gen_random_uuid(), ?, 'ACTIVE', 'Sachbearbeiter', 'de', 'Europe/Berlin', 0, false)
                    returning id
                    """, UUID.class, "fx-" + UUID.randomUUID().toString().substring(0, 10) + "@example.de");
            UUID roleId = jdbc.queryForObject("""
                    insert into roles (id, tenant_id, code, name, is_system)
                    values (gen_random_uuid(), ?, ?, '{"de":"Testrolle"}'::jsonb, false) returning id
                    """, UUID.class, tenantId, "ROLE_" + UUID.randomUUID().toString().substring(0, 8));
            for (String permission : permissions) {
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
        });
    }

    private void enable(UUID tenantId, String tool) {
        RequestContext.runAs(tenantId, () -> {
            policy.setToolEnabled(tenantId, tool, true, null);
            return null;
        });
    }

    private AssistantService.Turn turn(UUID tenantId, UUID userId) {
        return new AssistantService.Turn(tenantId, userId, "Bitte erledigen", "modell-1", "v1", "hash-1",
                UntrustedContent.Origin.PLATFORM, null);
    }

    private AssistantService.Answer ask(UUID tenantId, AssistantService.Turn turn, String tool,
                                        Map<String, Object> parameters) {
        return RequestContext.runAs(tenantId, () -> assistant.ask(turn, tool, parameters));
    }

    private UUID document(UUID tenantId, String name) {
        String hash = "%064x".formatted(java.util.concurrent.ThreadLocalRandom.current().nextLong());
        return jdbc.queryForObject("""
                insert into documents (id, tenant_id, original_filename, internal_name, mime_type,
                                       size_bytes, content_hash, source, processing_status, review_status)
                values (gen_random_uuid(), ?, ?, ?, 'application/pdf', 100, ?, 'UPLOAD', 'READY',
                        'NOT_REQUIRED')
                returning id
                """, UUID.class, tenantId, name, name, hash);
    }

    private UUID caseRecord(UUID tenantId, String reference) {
        return jdbc.queryForObject("""
                insert into cases (id, tenant_id, reference, title)
                values (gen_random_uuid(), ?, ?, ?) returning id
                """, UUID.class, tenantId, reference, "Vorgang " + reference);
    }

    private UUID task(UUID tenantId, String title) {
        return jdbc.queryForObject("""
                insert into tasks (id, tenant_id, title, status, priority, priority_source, source,
                                   created_by_user_id)
                values (gen_random_uuid(), ?, ?, 'NEW', 'NORMAL', 'AI', 'AI', null) returning id
                """, UUID.class, tenantId, title);
    }

    @Test
    @DisplayName("move_document moves after confirmation, and a document of another tenant is not found")
    void moveDocumentMovesAndRefusesForeign() {
        UUID tenantId = tenant();
        UUID user = person(tenantId, "documents.edit");
        enable(tenantId, "move_document");
        UUID documentId = document(tenantId, "rechnung.pdf");
        UUID caseId = caseRecord(tenantId, "VA-1");

        AssistantService.Answer proposal = ask(tenantId, turn(tenantId, user), "move_document",
                Map.of("documentId", documentId.toString(), "caseId", caseId.toString()));

        assertThat(proposal.needsConfirmation()).isTrue();
        assertThat(proposal.preview()).contains("rechnung.pdf").contains("VA-1");
        assertThat(jdbc.queryForObject("select count(*) from case_documents where document_id = ?",
                Integer.class, documentId)).as("nothing may happen before the person confirms").isZero();

        RequestContext.runAs(tenantId, () -> assistant.confirm(tenantId, user, proposal.proposalId()));

        assertThat(jdbc.queryForObject("""
                select count(*) from case_documents where document_id = ? and case_id = ?
                """, Integer.class, documentId, caseId)).isEqualTo(1);

        UUID stranger = tenant();
        UUID strangerUser = person(stranger, "documents.edit");
        enable(stranger, "move_document");
        AssistantService.Answer foreign = ask(stranger, turn(stranger, strangerUser), "move_document",
                Map.of("documentId", documentId.toString(), "caseId", caseId.toString()));

        assertThatThrownBy(() -> RequestContext.runAs(stranger,
                () -> assistant.confirm(stranger, strangerUser, foreign.proposalId())))
                .as("a document of another tenant must not even be nameable")
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("nicht gefunden");
    }

    @Test
    @DisplayName("delete_document previews the name and deletes softly after confirmation")
    void deleteDocumentIsSoft() {
        UUID tenantId = tenant();
        UUID user = person(tenantId, "documents.delete");
        enable(tenantId, "delete_document");
        UUID documentId = document(tenantId, "alt.pdf");

        AssistantService.Answer proposal = ask(tenantId, turn(tenantId, user), "delete_document",
                Map.of("documentId", documentId.toString()));

        assertThat(proposal.preview()).contains("alt.pdf").contains("gelöscht");
        assertThat(jdbc.queryForObject("select count(*) from documents where id = ? and deleted_at is null",
                Integer.class, documentId)).isEqualTo(1);

        RequestContext.runAs(tenantId, () -> assistant.confirm(tenantId, user, proposal.proposalId()));

        assertThat(jdbc.queryForObject("select count(*) from documents where id = ? and deleted_at is null",
                Integer.class, documentId)).as("the row stays, marked deleted").isZero();
    }

    @Test
    @DisplayName("update_case changes the named fields after confirmation")
    void updateCaseChangesFields() {
        UUID tenantId = tenant();
        UUID user = person(tenantId, "cases.edit");
        enable(tenantId, "update_case");
        UUID caseId = caseRecord(tenantId, "VA-2");

        AssistantService.Answer proposal = ask(tenantId, turn(tenantId, user), "update_case",
                Map.of("caseId", caseId.toString(), "title", "Neuer Titel", "priority", "high"));

        assertThat(proposal.preview()).contains("VA-2");
        RequestContext.runAs(tenantId, () -> assistant.confirm(tenantId, user, proposal.proposalId()));

        assertThat(jdbc.queryForObject("select title from cases where id = ?", String.class, caseId))
                .isEqualTo("Neuer Titel");
        assertThat(jdbc.queryForObject("select priority from cases where id = ?", String.class, caseId))
                .isEqualTo("HIGH");
    }

    @Test
    @DisplayName("create_calendar_event enters the calendar after confirmation")
    void createCalendarEventEntersIt() {
        UUID tenantId = tenant();
        UUID user = person(tenantId, "tasks.create");
        enable(tenantId, "create_calendar_event");

        AssistantService.Answer proposal = ask(tenantId, turn(tenantId, user), "create_calendar_event",
                Map.of("title", "Zahnarzt", "date", "2026-11-05"));

        assertThat(proposal.needsConfirmation()).isTrue();
        RequestContext.runAs(tenantId, () -> assistant.confirm(tenantId, user, proposal.proposalId()));

        // calendar_events has FORCE row level security: even this assertion must run in the tenant context.
        assertThat(RequestContext.runAs(tenantId, () -> jdbc.queryForObject(
                "select count(*) from calendar_events where tenant_id = ? and title = ?",
                Integer.class, tenantId, "Zahnarzt"))).isEqualTo(1);
    }

    @Test
    @DisplayName("create_deadline notes a deadline for this tenant's task only")
    void createDeadlineNeedsTheOwnTask() {
        UUID tenantId = tenant();
        UUID user = person(tenantId, "tasks.edit");
        enable(tenantId, "create_deadline");
        UUID taskId = task(tenantId, "Steuer prüfen");

        AssistantService.Answer proposal = ask(tenantId, turn(tenantId, user), "create_deadline",
                Map.of("taskId", taskId.toString(), "dueDate", "2026-11-10"));

        assertThat(proposal.needsConfirmation()).isTrue();
        RequestContext.runAs(tenantId, () -> assistant.confirm(tenantId, user, proposal.proposalId()));

        assertThat(jdbc.queryForObject("select count(*) from task_deadlines where task_id = ?",
                Integer.class, taskId)).isEqualTo(1);

        UUID stranger = tenant();
        UUID strangerUser = person(stranger, "tasks.edit");
        enable(stranger, "create_deadline");
        AssistantService.Answer foreign = ask(stranger, turn(stranger, strangerUser), "create_deadline",
                Map.of("taskId", taskId.toString(), "dueDate", "2026-11-10"));

        assertThatThrownBy(() -> RequestContext.runAs(stranger,
                () -> assistant.confirm(stranger, strangerUser, foreign.proposalId())))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("nicht gefunden");
    }

    @Test
    @DisplayName("send_email creates a draft after confirmation and never sends")
    void sendEmailCreatesDraft() {
        UUID tenantId = tenant();
        UUID user = person(tenantId, "mail.send");
        enable(tenantId, "send_email");

        AssistantService.Answer proposal = ask(tenantId, turn(tenantId, user), "send_email",
                Map.of("to", "kunde@example.de", "subject", "Ihr Vorgang", "body", "Anbei."));

        assertThat(proposal.preview()).contains("Entwurf").contains("kunde@example.de");
        RequestContext.runAs(tenantId, () -> assistant.confirm(tenantId, user, proposal.proposalId()));

        assertThat(jdbc.queryForObject("""
                select count(*) from outbound_messages where tenant_id = ? and status = 'DRAFT'
                  and subject = 'Ihr Vorgang'
                """, Integer.class, tenantId)).isEqualTo(1);
    }

    @Test
    @DisplayName("list_screens names the areas of the application without any permission")
    void listScreensNeedsNoPermission() {
        UUID tenantId = tenant();
        UUID user = person(tenantId);

        AssistantService.Answer answer = ask(tenantId, turn(tenantId, user), "list_screens", Map.of());

        assertThat(answer.isAnswer()).isTrue();
        assertThat(String.valueOf(answer.result()))
                .contains("/dashboard").contains("/calendar").contains("/settings");
    }
}
