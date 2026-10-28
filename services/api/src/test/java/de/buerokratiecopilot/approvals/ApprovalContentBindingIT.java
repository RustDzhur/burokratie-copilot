package de.buerokratiecopilot.approvals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.approvals.domain.Payment;
import de.buerokratiecopilot.approvals.domain.PaymentApproval;
import de.buerokratiecopilot.mail.outbound.OutboundMessageService;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.support.TenantContextExtension;
import de.buerokratiecopilot.workflow.approval.ApprovalService;

/**
 * CA-04: a confirmation is bound to the content it was given for. A payment whose receiving account changes after it was
 * confirmed, or a letter whose text changes after it was released, is not executed; the old confirmation expires and a new
 * one is requested.
 */
@SpringBootTest
@ExtendWith(TenantContextExtension.class)
@ActiveProfiles("test")
class ApprovalContentBindingIT {

    @Autowired
    private PaymentApprovalService payments;
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
        jdbc.update("""
                insert into payment_approval_rules (id, tenant_id, code, name, min_amount, currency, direction,
                                                    required_permission, approval_levels, require_second_person, is_active, version)
                values (gen_random_uuid(), ?, 'over-500', '{"de":"Zahlungen über 500 EUR"}'::jsonb, 500.00, 'EUR',
                        'OUTGOING', 'finance.approve', 1, false, true, 1)
                """, tenantId);
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

    private PaymentApprovalService.Prepared prepare(UUID tenantId, UUID requester) {
        return RequestContext.runAs(tenantId, () -> payments.prepare(new PaymentApprovalService.PrepareRequest(tenantId, null, null,
                new BigDecimal("1190.00"), "EUR", "Muster GmbH", "DE89370400440532013000", "RE-2026-001", LocalDate.of(2026, 4, 1)),
                requester));
    }

    @Test
    @DisplayName("a payment confirmed for one account is not executed after the account changed; the confirmation expires and a new request is raised")
    void paymentChangedAfterConfirmation() {
        UUID tenantId = tenant();
        UUID approver = person(tenantId, "finance.approve");
        UUID requester = person(tenantId, "finance.create");
        PaymentApprovalService.Prepared prepared = prepare(tenantId, requester);
        UUID paymentId = prepared.payment().getId();

        assertThat(jdbc.queryForObject("select content_hash from approvals where id = ?", String.class, prepared.approval().getId()))
                .as("the request is bound to the payment as requested").hasSize(64);

        RequestContext.runAs(tenantId, () -> payments.decide(prepared.levels().getFirst().getId(), tenantId, approver,
                PaymentApproval.Status.APPROVED, "Geprüft"));
        assertThat(jdbc.queryForObject("select status from approvals where id = ?", String.class, prepared.approval().getId()))
                .isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("select approved_content_hash from payments where id = ?", String.class, paymentId)).hasSize(64);

        // Somebody changes where the money goes, after the confirmation.
        jdbc.update("update payments set counterparty_iban = 'DE02120300000000202051' where id = ?", paymentId);

        assertThatThrownBy(() -> RequestContext.runAs(tenantId, () -> payments.execute(paymentId, tenantId, approver, "BANK-1")))
                .isInstanceOf(ApiException.class).hasMessageContaining("Freigabe");

        assertThat(jdbc.queryForObject("select status from payments where id = ?", String.class, paymentId)).isEqualTo("AWAITING_APPROVAL");
        assertThat(jdbc.queryForObject("select status from approvals where id = ?", String.class, prepared.approval().getId()))
                .as("the old confirmation no longer holds").isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("select count(*) from approvals where subject_id = ? and status = 'PENDING'", Integer.class, paymentId))
                .as("a new request waits for the changed payment").isEqualTo(1);

        // Trying again does not get through: nothing was confirmed for this content.
        assertThatThrownBy(() -> RequestContext.runAs(tenantId, () -> payments.execute(paymentId, tenantId, approver, "BANK-2")))
                .isInstanceOf(ApiException.class);
        assertThat(jdbc.queryForObject("select status from payments where id = ?", String.class, paymentId)).isNotEqualTo("EXECUTED");
    }

    @Test
    @DisplayName("an unchanged payment is executed once; executing it again is refused")
    void unchangedPaymentRuns() {
        UUID tenantId = tenant();
        UUID approver = person(tenantId, "finance.approve");
        UUID requester = person(tenantId, "finance.create");
        PaymentApprovalService.Prepared prepared = prepare(tenantId, requester);
        RequestContext.runAs(tenantId, () -> payments.decide(prepared.levels().getFirst().getId(), tenantId, approver,
                PaymentApproval.Status.APPROVED, "Geprüft"));

        Payment executed = RequestContext.runAs(tenantId, () -> payments.execute(prepared.payment().getId(), tenantId, approver, "BANK-1"));
        assertThat(executed.getStatus()).isEqualTo(Payment.Status.EXECUTED);
        assertThatThrownBy(() -> RequestContext.runAs(tenantId, () -> payments.execute(prepared.payment().getId(), tenantId, approver, "BANK-2")))
                .isInstanceOf(ApiException.class);
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
                "OUTBOUND_MESSAGE", "Kündigung senden", "Bitte prüfen", null, "OUTGOING", null, author));
        assertThat(jdbc.queryForObject("select content_hash from approvals where id = ?", String.class, request.approvalId())).hasSize(64);

        jdbc.update("update outbound_messages set body_text = 'Hiermit kündige ich ausserordentlich.' where id = ?", messageId);

        assertThatThrownBy(() -> RequestContext.runAs(tenantId, () -> approvals.decide(tenantId, request.approvalId(), approver, true, "ok")))
                .isInstanceOf(ApiException.class).hasMessageContaining("Inhalt");
        assertThat(jdbc.queryForObject("select status from approvals where id = ?", String.class, request.approvalId())).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("select count(*) from approvals where subject_id = ? and status = 'PENDING'", Integer.class, messageId))
                .isEqualTo(1);
    }
}
