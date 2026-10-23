package de.buerokratiecopilot.approvals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.mail.outbound.OutboundMessageService;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.support.TenantContextExtension;
import de.buerokratiecopilot.workflow.approval.ApprovalService;

/**
 * CA-04: a confirmation is bound to the content it was given for. A letter whose text changes after it was released is not
 * sent; the old confirmation expires and a new one is requested. The payment half of this test went with the payment
 * module (§4.3).
 */
@SpringBootTest
@ExtendWith(TenantContextExtension.class)
@ActiveProfiles("test")
class ApprovalContentBindingIT {

    @Autowired
    private OutboundMessageService outbound;
    @Autowired
    private ApprovalService approvals;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID tenant() {
        UUID tenantId = jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'Bindung Test', 'COMPANY') returning id
                """, UUID.class, "bind-" + UUID.randomUUID().toString().substring(0, 12));
        RequestContext.setTenantId(tenantId);
        return tenantId;
    }

    private UUID person(UUID tenantId, String permissionCode) {
        UUID userId = jdbc.queryForObject("""
                insert into users (id, email, status, display_name, locale, time_zone, failed_login_attempts, must_change_password)
                values (gen_random_uuid(), ?, 'ACTIVE', ?, 'de', 'Europe/Berlin', 0, false) returning id
                """, UUID.class, "b-" + UUID.randomUUID().toString().substring(0, 10) + "@example.de", permissionCode);
        UUID roleId = jdbc.queryForObject("""
                insert into roles (id, tenant_id, code, name, is_system)
                values (gen_random_uuid(), ?, ?, '{"de":"Testrolle"}'::jsonb, false) returning id
                """, UUID.class, tenantId, "ROLE_" + UUID.randomUUID().toString().substring(0, 8));
        jdbc.update("insert into role_permissions (role_id, permission_id) select ?, id from permissions where code = ?", roleId, permissionCode);
        UUID membershipId = jdbc.queryForObject("""
                insert into memberships (id, tenant_id, user_id, status, is_owner)
                values (gen_random_uuid(), ?, ?, 'ACTIVE', false) returning id
                """, UUID.class, tenantId, userId);
        jdbc.update("insert into membership_roles (membership_id, role_id, granted_at) values (?, ?, now())", membershipId, roleId);
        return userId;
    }

    @Test
    @DisplayName("a letter released with one text is not sent after its addressee changed; the release is withdrawn")
    void letterChangedAfterRelease() {
        UUID tenantId = tenant();
        UUID author = person(tenantId, "mail.send");
        UUID approver = person(tenantId, "mail.send");
        UUID messageId = outbound.draft(new OutboundMessageService.DraftRequest(tenantId, null, "EMAIL", "Widerspruch",
                "Sehr geehrte Damen und Herren, ich widerspreche.", null, "absender@example.de", List.of("empfaenger@example.de"),
                List.of(), null, null, author));
        outbound.approve(tenantId, messageId, approver, null, "Mit freundlichen Grüßen", null);
        assertThat(jdbc.queryForObject("select approved_content_hash from outbound_messages where id = ?", String.class, messageId)).hasSize(64);

        // Another recipient, after the release.
        jdbc.update("update outbound_messages set to_addresses = '[\"anderer@example.de\"]'::jsonb where id = ?", messageId);

        assertThatThrownBy(() -> outbound.send(tenantId, messageId, approver)).isInstanceOf(ApiException.class)
                .hasMessageContaining("Freigabe");
        assertThat(jdbc.queryForObject("select status from outbound_messages where id = ?", String.class, messageId))
                .isEqualTo("PENDING_APPROVAL");
        assertThat(jdbc.queryForObject("select approved_by from outbound_messages where id = ?", UUID.class, messageId))
                .as("the release is gone").isNull();
    }

    @Test
    @DisplayName("an approval of a letter whose text changes before the decision cannot be approved; it expires and is raised again")
    void changeBeforeDecision() {
        UUID tenantId = tenant();
        UUID author = person(tenantId, "mail.send");
        UUID approver = person(tenantId, "mail.send");
        UUID messageId = outbound.draft(new OutboundMessageService.DraftRequest(tenantId, null, "EMAIL", "Kündigung",
                "Hiermit kündige ich.", null, "absender@example.de", List.of("empfaenger@example.de"), List.of(), null, null, author));
        ApprovalService.Request request = RequestContext.runAs(tenantId, () -> approvals.request(tenantId, "OUTBOUND_MESSAGE", messageId,
                "OUTBOUND_MESSAGE", "Kündigung senden", "Bitte prüfen", null, author));
        assertThat(jdbc.queryForObject("select content_hash from approvals where id = ?", String.class, request.approvalId())).hasSize(64);

        jdbc.update("update outbound_messages set body_text = 'Hiermit kündige ich ausserordentlich.' where id = ?", messageId);

        assertThatThrownBy(() -> RequestContext.runAs(tenantId, () -> approvals.decide(tenantId, request.approvalId(), approver, true, "ok")))
                .isInstanceOf(ApiException.class).hasMessageContaining("Inhalt");
        assertThat(jdbc.queryForObject("select status from approvals where id = ?", String.class, request.approvalId())).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("select count(*) from approvals where subject_id = ? and status = 'PENDING'", Integer.class, messageId))
                .isEqualTo(1);
    }
}
