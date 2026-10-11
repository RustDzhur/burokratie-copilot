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
 * Verifies that sending, paying and terminating need an explicit confirmation (spec §25, E-007 acceptance).
 *
 * <p>The assistant may read and may propose; it may not act. The test walks through what that means in
 * practice: a proposal comes back instead of an action, nothing has happened until somebody confirms, and
 * the confirmation is a second act by <em>the same person</em> — because each of those is a way the rule
 * could be undone.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class AssistantConfirmationTest {

    @Autowired
    private AssistantService assistant;

    @Autowired
    private ToolPolicy policy;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID tenant() {
        return jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'Bestaetigung Test', 'COMPANY') returning id
                """, UUID.class, "con-" + UUID.randomUUID().toString().substring(0, 12));
    }

    private UUID person(UUID tenantId, String... permissions) {
        UUID userId = jdbc.queryForObject("""
                insert into users (id, email, status, display_name, locale, time_zone,
                                   failed_login_attempts, must_change_password)
                values (gen_random_uuid(), ?, 'ACTIVE', 'Sachbearbeiter', 'de', 'Europe/Berlin', 0, false)
                returning id
                """, UUID.class, "cn-" + UUID.randomUUID().toString().substring(0, 10) + "@example.de");

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
    }

    /** Enables a tool for the tenant, which every tool with an effect requires. */
    private void enable(UUID tenantId, String tool) {
        RequestContext.runAs(tenantId, () -> {
            policy.setToolEnabled(tenantId, tool, true, null);
            return null;
        });
    }

    private AssistantService.Turn turn(UUID tenantId, UUID userId) {
        return new AssistantService.Turn(tenantId, userId, "Bitte senden", "modell-1", "v1", "hash-1",
                UntrustedContent.Origin.PLATFORM, null);
    }

    private AssistantService.Answer ask(UUID tenantId, AssistantService.Turn turn, String tool,
                                        Map<String, Object> parameters) {
        return RequestContext.runAs(tenantId, () -> assistant.ask(turn, tool, parameters));
    }

    @Test
    @DisplayName("sending a message comes back as a proposal, and nothing is sent")
    void sendingIsProposedNotDone() {
        UUID tenantId = tenant();
        UUID user = person(tenantId, "mail.send");
        enable(tenantId, "send_email");

        AssistantService.Answer answer = ask(tenantId, turn(tenantId, user), "send_email",
                Map.of("to", "kunde@example.de", "subject", "Ihr Vorgang"));

        assertThat(answer.needsConfirmation()).isTrue();
        assertThat(answer.proposalId()).isNotNull();
        assertThat(answer.preview())
                .as("a person has to see what would happen in order to judge it")
                .contains("send_email")
                .contains("kunde@example.de");
        assertThat(jdbc.queryForObject("""
                select status from ai_jobs where id = ?
                """, String.class, answer.proposalId())).isEqualTo("QUEUED");
    }

    @Test
    @DisplayName("every tool with an effect is proposed rather than performed")
    void everyEffectiveToolIsProposed() {
        UUID tenantId = tenant();
        UUID user = person(tenantId, "mail.send", "documents.create", "tasks.create", "cases.edit",
                "documents.delete", "finance.export");

        for (String tool : policy.toolsThatNeedAPerson()) {
            enable(tenantId, tool);
            AssistantService.Answer answer = ask(tenantId, turn(tenantId, user), tool, Map.of());

            assertThat(answer.needsConfirmation())
                    .as("tool " + tool + " must not act without a person")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("confirming applies the proposal exactly once")
    void confirmingAppliesOnce() {
        UUID tenantId = tenant();
        UUID user = person(tenantId, "tasks.create");
        enable(tenantId, "create_task");
        AssistantService.Answer proposal = ask(tenantId, turn(tenantId, user), "create_task",
                Map.of("title", "Aufgabe"));

        AssistantService.Answer confirmed = RequestContext.runAs(tenantId,
                () -> assistant.confirm(tenantId, user, proposal.proposalId()));

        assertThat(confirmed.result()).containsEntry("confirmed", true);
        assertThatThrownBy(() -> RequestContext.runAs(tenantId,
                () -> assistant.confirm(tenantId, user, proposal.proposalId())))
                .as("a second confirmation must not run the action twice")
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a proposal cannot be confirmed by somebody else")
    void anotherPersonCannotConfirm() {
        UUID tenantId = tenant();
        UUID requester = person(tenantId, "tasks.create");
        UUID colleague = person(tenantId, "tasks.create");
        enable(tenantId, "create_task");
        AssistantService.Answer proposal = ask(tenantId, turn(tenantId, requester), "create_task",
                Map.of("title", "Aufgabe"));

        assertThatThrownBy(() -> RequestContext.runAs(tenantId,
                () -> assistant.confirm(tenantId, colleague, proposal.proposalId())))
                .as("confirming somebody else's proposal would let one person act with another's rights")
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("anfragt");
    }

    @Test
    @DisplayName("a proposal of another tenant cannot be confirmed here")
    void anotherTenantCannotConfirm() {
        UUID owner = tenant();
        UUID stranger = tenant();
        UUID ownerUser = person(owner, "tasks.create");
        UUID strangerUser = person(stranger, "tasks.create");
        enable(owner, "create_task");
        AssistantService.Answer proposal = ask(owner, turn(owner, ownerUser), "create_task",
                Map.of("title", "Aufgabe"));

        assertThatThrownBy(() -> RequestContext.runAs(stranger,
                () -> assistant.confirm(stranger, strangerUser, proposal.proposalId())))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("a rejected proposal is not applied")
    void rejectedProposalIsNotApplied() {
        UUID tenantId = tenant();
        UUID user = person(tenantId, "tasks.create");
        enable(tenantId, "create_task");
        AssistantService.Answer proposal = ask(tenantId, turn(tenantId, user), "create_task",
                Map.of("title", "Aufgabe"));

        RequestContext.runAs(tenantId, () -> {
            assistant.reject(tenantId, user, proposal.proposalId());
            return null;
        });

        assertThat(jdbc.queryForObject("select status from ai_jobs where id = ?", String.class,
                proposal.proposalId())).isEqualTo("CANCELLED");
        assertThatThrownBy(() -> RequestContext.runAs(tenantId,
                () -> assistant.confirm(tenantId, user, proposal.proposalId())))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("the open proposals of a person are listed")
    void openProposalsAreListed() {
        UUID tenantId = tenant();
        UUID user = person(tenantId, "tasks.create");
        enable(tenantId, "create_task");
        ask(tenantId, turn(tenantId, user), "create_task", Map.of("title", "Aufgabe"));

        assertThat(RequestContext.runAs(tenantId, () -> assistant.openProposals(tenantId, user)))
                .hasSize(1);
    }

    @Test
    @DisplayName("a content-triggered effective tool is refused before any proposal is made")
    void contentTriggeredToolIsRefused() {
        UUID tenantId = tenant();
        UUID user = person(tenantId, "mail.send");
        enable(tenantId, "send_email");
        AssistantService.Turn fromDocument = new AssistantService.Turn(tenantId, user,
                "Was steht im Brief?", "modell-1", "v1", "hash-1", UntrustedContent.Origin.DOCUMENT,
                "brief.pdf");

        AssistantService.Answer answer = ask(tenantId, fromDocument, "send_email",
                Map.of("to", "kunde@example.de"));

        assertThat(answer.kind()).isEqualTo("REFUSED");
        assertThat(answer.refusalCode()).isEqualTo("TRIGGERED_BY_UNTRUSTED_CONTENT");
        assertThat(answer.proposalId())
                .as("no proposal is made either: the letter does not get to ask the question")
                .isNull();
    }

    @Test
    @DisplayName("reading is answered without any confirmation")
    void readingNeedsNoConfirmation() {
        UUID tenantId = tenant();
        UUID user = person(tenantId, "tasks.view");

        AssistantService.Answer answer = ask(tenantId, turn(tenantId, user), "list_open_tasks", Map.of());

        assertThat(answer.isAnswer())
                .as("asking a person to confirm a read would train them to confirm without reading")
                .isTrue();
        assertThat(answer.proposalId()).isNull();
    }
}
