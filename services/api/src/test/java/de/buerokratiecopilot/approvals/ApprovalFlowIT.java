package de.buerokratiecopilot.approvals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.approvals.domain.Payment;
import de.buerokratiecopilot.approvals.domain.PaymentApproval;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * Verifies the payment approval flow (spec §15, D-004 acceptance).
 *
 * <p>The criterion is that a payment is not executed without a confirmation by an authorised person.
 * The test therefore tries every route around that: executing an unprepared payment, approving without
 * the required role, and having one person decide both levels of a four-eyes rule. Each has to be
 * refused, and the refusal has to say why.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class ApprovalFlowIT {

    @Autowired
    private PaymentApprovalService approvals;

    @Autowired
    private JdbcTemplate jdbc;

    private record Tenant(UUID tenantId, UUID requesterId, UUID approverId, UUID outsiderId) {
    }

    /** A tenant with a rule that demands the finance.approve permission for payments over 500. */
    private Tenant tenant(boolean secondPerson, int levels) {
        UUID tenantId = jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'Freigabe Test', 'COMPANY') returning id
                """, UUID.class, "appr-" + UUID.randomUUID().toString().substring(0, 12));

        jdbc.update("""
                insert into payment_approval_rules (id, tenant_id, code, name, min_amount, currency,
                                                    direction, required_permission, approval_levels,
                                                    require_second_person, is_active, version)
                values (gen_random_uuid(), ?, 'over-500',
                        '{"de":"Zahlungen über 500 EUR"}'::jsonb, 500.00, 'EUR',
                        'OUTGOING', 'finance.approve', ?, ?, true, 1)
                """, tenantId, levels, secondPerson);

        UUID approverId = person(tenantId, "finance.approve");
        UUID requesterId = person(tenantId, "finance.create");
        UUID outsiderId = person(tenantId, "tasks.view");
        return new Tenant(tenantId, requesterId, approverId, outsiderId);
    }

    /** A person with exactly one permission, through a role created for the purpose. */
    private UUID person(UUID tenantId, String permissionCode) {
        UUID userId = jdbc.queryForObject("""
                insert into users (id, email, status, display_name, locale, time_zone,
                                   failed_login_attempts, must_change_password)
                values (gen_random_uuid(), ?, 'ACTIVE', ?, 'de', 'Europe/Berlin', 0, false)
                returning id
                """, UUID.class, "p-" + UUID.randomUUID().toString().substring(0, 10) + "@example.de",
                permissionCode);

        UUID roleId = jdbc.queryForObject("""
                insert into roles (id, tenant_id, code, name, is_system)
                values (gen_random_uuid(), ?, ?, '{"de":"Testrolle"}'::jsonb, false) returning id
                """, UUID.class, tenantId, "ROLE_" + UUID.randomUUID().toString().substring(0, 8));

        jdbc.update("""
                insert into role_permissions (role_id, permission_id)
                select ?, id from permissions where code = ?
                """, roleId, permissionCode);

        UUID membershipId = jdbc.queryForObject("""
                insert into memberships (id, tenant_id, user_id, status, is_owner)
                values (gen_random_uuid(), ?, ?, 'ACTIVE', false) returning id
                """, UUID.class, tenantId, userId);

        jdbc.update("insert into membership_roles (membership_id, role_id, granted_at) values (?, ?, now())",
                membershipId, roleId);
        return userId;
    }

    private PaymentApprovalService.Prepared prepare(Tenant tenant, String amount) {
        return RequestContext.runAs(tenant.tenantId(), () -> approvals.prepare(
                new PaymentApprovalService.PrepareRequest(tenant.tenantId(), null, null,
                        new BigDecimal(amount), "EUR", "Muster GmbH", "DE89370400440532013000",
                        "RE-2026-001", LocalDate.of(2026, 4, 1)),
                tenant.requesterId()));
    }

    @Test
    @DisplayName("a prepared payment waits for a decision and cannot be executed")
    void preparedPaymentWaits() {
        Tenant tenant = tenant(false, 1);
        PaymentApprovalService.Prepared prepared = prepare(tenant, "1190.00");

        assertThat(prepared.payment().getStatus()).isEqualTo(Payment.Status.AWAITING_APPROVAL);
        assertThat(prepared.levels()).hasSize(1);
        assertThat(prepared.rule()).isNotNull();

        // The acceptance criterion: no route from prepared to executed that skips a person.
        assertThatThrownBy(() -> RequestContext.runAs(tenant.tenantId(), () -> approvals.execute(
                prepared.payment().getId(), tenant.tenantId(), tenant.approverId(), "BANK-REF-1")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("freigegeben");
    }

    @Test
    @DisplayName("approving the level confirms the payment, and only then may it be executed")
    void approvalConfirmsAndReleases() {
        Tenant tenant = tenant(false, 1);
        PaymentApprovalService.Prepared prepared = prepare(tenant, "1190.00");

        PaymentApproval level = RequestContext.runAs(tenant.tenantId(), () -> approvals.decide(
                prepared.levels().getFirst().getId(), tenant.tenantId(), tenant.approverId(),
                PaymentApproval.Status.APPROVED, "Geprüft"));

        assertThat(level.getStatus()).isEqualTo(PaymentApproval.Status.APPROVED);
        assertThat(level.getApproverUserId()).isEqualTo(tenant.approverId());

        Payment executed = RequestContext.runAs(tenant.tenantId(), () -> approvals.execute(
                prepared.payment().getId(), tenant.tenantId(), tenant.approverId(), "BANK-REF-2"));
        assertThat(executed.getStatus()).isEqualTo(Payment.Status.EXECUTED);
        assertThat(executed.getExecutedAt()).isNotNull();
    }

    @Test
    @DisplayName("a person without the required permission cannot approve")
    void unauthorisedPersonCannotApprove() {
        Tenant tenant = tenant(false, 1);
        PaymentApprovalService.Prepared prepared = prepare(tenant, "1190.00");

        assertThatThrownBy(() -> RequestContext.runAs(tenant.tenantId(), () -> approvals.decide(
                prepared.levels().getFirst().getId(), tenant.tenantId(), tenant.outsiderId(),
                PaymentApproval.Status.APPROVED, "Ich mach das mal")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("freigegeben werden");
    }

    @Test
    @DisplayName("a payment below the rule's threshold needs no rule, and one level is still created")
    void belowThresholdStillNeedsAPerson() {
        Tenant tenant = tenant(false, 1);
        // The rule starts at 500, so a payment of 100 matches no rule; the platform does not therefore
        // let it execute itself: one level remains, and a person still decides.
        PaymentApprovalService.Prepared prepared = prepare(tenant, "100.00");

        assertThat(prepared.rule()).isNull();
        assertThat(prepared.levels()).hasSize(1);
        assertThat(prepared.payment().getStatus()).isEqualTo(Payment.Status.AWAITING_APPROVAL);

        assertThatThrownBy(() -> RequestContext.runAs(tenant.tenantId(), () -> approvals.execute(
                prepared.payment().getId(), tenant.tenantId(), tenant.approverId(), null)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a four-eyes rule refuses the same person on both levels")
    void fourEyesNeedsTwoPeople() {
        Tenant tenant = tenant(true, 2);
        PaymentApprovalService.Prepared prepared = prepare(tenant, "5000.00");

        assertThat(prepared.levels()).hasSize(2);

        RequestContext.runAs(tenant.tenantId(), () -> approvals.decide(
                prepared.levels().get(0).getId(), tenant.tenantId(), tenant.approverId(),
                PaymentApproval.Status.APPROVED, "Erste Stufe"));

        // The rule demands two different people, and the platform says so instead of accepting a click.
        assertThatThrownBy(() -> RequestContext.runAs(tenant.tenantId(), () -> approvals.decide(
                prepared.levels().get(1).getId(), tenant.tenantId(), tenant.approverId(),
                PaymentApproval.Status.APPROVED, "Zweite Stufe, dieselbe Person")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("zwei verschiedene Personen");
    }

    @Test
    @DisplayName("a rejection on one level stops the payment and skips the rest")
    void rejectionStopsThePayment() {
        Tenant tenant = tenant(true, 2);
        PaymentApprovalService.Prepared prepared = prepare(tenant, "5000.00");

        RequestContext.runAs(tenant.tenantId(), () -> approvals.decide(
                prepared.levels().get(0).getId(), tenant.tenantId(), tenant.approverId(),
                PaymentApproval.Status.REJECTED, "Betrag stimmt nicht"));

        List<PaymentApproval> levels = RequestContext.runAs(tenant.tenantId(),
                () -> approvals.levelsOf(prepared.payment().getId()));

        assertThat(levels).allSatisfy(level ->
                assertThat(level.getStatus()).isNotEqualTo(PaymentApproval.Status.PENDING));
        Payment payment = RequestContext.runAs(tenant.tenantId(),
                () -> approvals.requirePayment(prepared.payment().getId(), tenant.tenantId()));
        assertThat(payment.isConfirmed())
                .as("a rejected payment must not end up confirmed")
                .isFalse();
    }

    @Test
    @DisplayName("a decided level cannot be decided again")
    void levelCannotBeDecidedTwice() {
        Tenant tenant = tenant(false, 1);
        PaymentApprovalService.Prepared prepared = prepare(tenant, "1190.00");

        RequestContext.runAs(tenant.tenantId(), () -> approvals.decide(
                prepared.levels().getFirst().getId(), tenant.tenantId(), tenant.approverId(),
                PaymentApproval.Status.APPROVED, "Geprüft"));

        assertThatThrownBy(() -> RequestContext.runAs(tenant.tenantId(), () -> approvals.decide(
                prepared.levels().getFirst().getId(), tenant.tenantId(), tenant.approverId(),
                PaymentApproval.Status.REJECTED, "Doch nicht")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("bereits entschieden");
    }

    @Test
    @DisplayName("automatic execution is off in the base configuration")
    void automaticExecutionIsOffByDefault() {
        Tenant tenant = tenant(false, 1);

        assertThat(RequestContext.runAs(tenant.tenantId(),
                () -> approvals.automaticExecutionAllowed(tenant.tenantId())))
                .as("the specification forbids automatic execution in the base configuration")
                .isFalse();
        assertThat(RequestContext.runAs(tenant.tenantId(),
                () -> approvals.configuredThreshold(tenant.tenantId())))
                .as("no threshold is invented when the tenant configured none")
                .isNull();
    }
}
