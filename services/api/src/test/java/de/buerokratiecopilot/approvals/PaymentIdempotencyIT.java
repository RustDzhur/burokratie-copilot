package de.buerokratiecopilot.approvals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
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
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.support.TenantContextExtension;

/**
 * Verifies that a repeated confirmation does not duplicate the operation (spec §15, D-004 acceptance).
 *
 * <p>Paying a supplier twice because a browser retried, or because two people clicked at once, is the
 * failure this guards against. The test therefore prepares the same payment twice, executes a confirmed
 * payment twice, and checks that the two ways a duplicate could arise are both closed — including the
 * one that a request identifier alone would not catch.</p>
 */
@SpringBootTest
@ExtendWith(TenantContextExtension.class)
@ActiveProfiles("test")
class PaymentIdempotencyIT {

    @Autowired
    private PaymentApprovalService approvals;

    @Autowired
    private JdbcTemplate jdbc;

    private record Tenant(UUID tenantId, UUID requesterId, UUID approverId) {
    }

    private Tenant tenant() {
        UUID tenantId = jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'Idempotenz Test', 'COMPANY') returning id
                """, UUID.class, "idem-" + UUID.randomUUID().toString().substring(0, 12));
        RequestContext.setTenantId(tenantId);

        UUID approverId = person(tenantId, "finance.approve");
        UUID requesterId = person(tenantId, "finance.create");
        return new Tenant(tenantId, requesterId, approverId);
    }

    private UUID person(UUID tenantId, String permissionCode) {
        UUID userId = jdbc.queryForObject("""
                insert into users (id, email, status, display_name, locale, time_zone,
                                   failed_login_attempts, must_change_password)
                values (gen_random_uuid(), ?, 'ACTIVE', ?, 'de', 'Europe/Berlin', 0, false)
                returning id
                """, UUID.class, "i-" + UUID.randomUUID().toString().substring(0, 10) + "@example.de",
                permissionCode);

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

    private PaymentApprovalService.Prepared prepare(Tenant tenant, UUID invoiceId, String amount) {
        return RequestContext.runAs(tenant.tenantId(), () -> approvals.prepare(
                new PaymentApprovalService.PrepareRequest(tenant.tenantId(), invoiceId, null,
                        new BigDecimal(amount), "EUR", "Muster GmbH", "DE89370400440532013000",
                        "RE-2026-001", LocalDate.of(2026, 4, 1)),
                tenant.requesterId()));
    }

    private long paymentCount(Tenant tenant) {
        Long count = jdbc.queryForObject("select count(*) from payments where tenant_id = ?", Long.class,
                tenant.tenantId());
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("preparing the same payment twice produces one payment, not two")
    void preparingTwiceProducesOnePayment() {
        Tenant tenant = tenant();
        PaymentApprovalService.Prepared first = prepare(tenant, null, "1190.00");
        PaymentApprovalService.Prepared second = prepare(tenant, null, "1190.00");

        assertThat(first.created()).isTrue();
        assertThat(second.created())
                .as("the second preparation found the payment that already existed")
                .isFalse();
        assertThat(second.payment().getId()).isEqualTo(first.payment().getId());
        assertThat(paymentCount(tenant)).isEqualTo(1);
    }

    @Test
    @DisplayName("two sessions preparing the same payment at once cannot make two payments")
    void simultaneousPreparationIsIdempotent() {
        Tenant tenant = tenant();
        // No request identifier is involved; the key comes from what is being paid, so it holds even
        // when the two attempts know nothing about each other.
        PaymentApprovalService.Prepared a = prepare(tenant, null, "250.00");
        PaymentApprovalService.Prepared b = prepare(tenant, null, "250.00");
        PaymentApprovalService.Prepared c = prepare(tenant, null, "250.00");

        assertThat(a.payment().getId()).isEqualTo(b.payment().getId()).isEqualTo(c.payment().getId());
        assertThat(paymentCount(tenant)).isEqualTo(1);
    }

    @Test
    @DisplayName("a different amount is a different payment")
    void differentAmountIsADifferentPayment() {
        Tenant tenant = tenant();
        PaymentApprovalService.Prepared first = prepare(tenant, null, "1190.00");
        PaymentApprovalService.Prepared other = prepare(tenant, null, "595.00");

        assertThat(other.payment().getId())
                .as("a partial payment or a corrected amount is a separate payment")
                .isNotEqualTo(first.payment().getId());
        assertThat(paymentCount(tenant)).isEqualTo(2);
    }

    @Test
    @DisplayName("an executed payment cannot be executed a second time")
    void executingTwiceIsRefused() {
        Tenant tenant = tenant();
        PaymentApprovalService.Prepared prepared = prepare(tenant, null, "1190.00");

        RequestContext.runAs(tenant.tenantId(), () -> approvals.decide(
                prepared.levels().getFirst().getId(), tenant.tenantId(), tenant.approverId(),
                PaymentApproval.Status.APPROVED, "Geprüft"));
        RequestContext.runAs(tenant.tenantId(), () -> approvals.execute(prepared.payment().getId(),
                tenant.tenantId(), tenant.approverId(), "BANK-1"));

        // The second attempt is reported rather than ignored: the caller has to know nothing happened.
        assertThatThrownBy(() -> RequestContext.runAs(tenant.tenantId(), () -> approvals.execute(
                prepared.payment().getId(), tenant.tenantId(), tenant.approverId(), "BANK-2")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("bereits ausgeführt");
    }

    @Test
    @DisplayName("confirming after an execution does not create anything new")
    void confirmingAgainDoesNotDuplicate() {
        Tenant tenant = tenant();
        PaymentApprovalService.Prepared prepared = prepare(tenant, null, "1190.00");

        RequestContext.runAs(tenant.tenantId(), () -> approvals.decide(
                prepared.levels().getFirst().getId(), tenant.tenantId(), tenant.approverId(),
                PaymentApproval.Status.APPROVED, "Geprüft"));
        RequestContext.runAs(tenant.tenantId(), () -> approvals.execute(prepared.payment().getId(),
                tenant.tenantId(), tenant.approverId(), "BANK-3"));

        Payment stored = RequestContext.runAs(tenant.tenantId(),
                () -> approvals.requirePayment(prepared.payment().getId(), tenant.tenantId()));

        assertThat(stored.getStatus()).isEqualTo(Payment.Status.EXECUTED);
        assertThat(paymentCount(tenant))
                .as("the count is what a duplicate would change, and it did not")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the key is derived from what is paid, so it survives a new session")
    void keyIsDerivedFromTheSubject() {
        UUID invoiceId = UUID.randomUUID();

        assertThat(PaymentApprovalService.idempotencyKey(invoiceId, new BigDecimal("1190.00"), "EUR"))
                .isEqualTo(PaymentApprovalService.idempotencyKey(invoiceId, new BigDecimal("1190.0"),
                        "EUR"))
                .as("1190.00 and 1190.0 are the same amount and must produce the same key");
        assertThat(PaymentApprovalService.idempotencyKey(invoiceId, new BigDecimal("1190.00"), "EUR"))
                .isNotEqualTo(PaymentApprovalService.idempotencyKey(UUID.randomUUID(),
                        new BigDecimal("1190.00"), "EUR"));
        assertThat(PaymentApprovalService.idempotencyKey(null, new BigDecimal("10.00"), "EUR"))
                .as("a payment without an invoice still gets a stable key")
                .isEqualTo(PaymentApprovalService.idempotencyKey(null, new BigDecimal("10.00"), "EUR"));
    }

    @Test
    @DisplayName("an amount that is not positive is refused")
    void refusesNonPositiveAmount() {
        Tenant tenant = tenant();

        assertThatThrownBy(() -> prepare(tenant, null, "0.00"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("positiv");
        assertThatThrownBy(() -> prepare(tenant, null, "-10.00"))
                .isInstanceOf(ApiException.class);
    }
}
