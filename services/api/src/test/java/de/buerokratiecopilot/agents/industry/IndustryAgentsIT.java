package de.buerokratiecopilot.agents.industry;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.agents.banking.BankFeeAuditAgent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.it.AccessReviewAgent;
import de.buerokratiecopilot.agents.logistics.DeliveryTrackingAgent;
import de.buerokratiecopilot.support.TestDataFactory;

/**
 * End-to-end runs of the industry agents whose work is a SQL query: access review, fee audit and
 * delivery tracking. The pure logic is unit-tested; this class proves the queries against the real
 * schema, including the tenant scope.
 */
@SpringBootTest
@ActiveProfiles("test")
class IndustryAgentsIT {

    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");
    private static final LocalDate TODAY = LocalDate.ofInstant(NOW, ZoneId.of("Europe/Berlin"));

    @Autowired
    private AccessReviewAgent accessReview;

    @Autowired
    private BankFeeAuditAgent feeAudit;

    @Autowired
    private DeliveryTrackingAgent deliveryTracking;

    @Autowired
    private TestDataFactory factory;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void accessReviewFindsAMemberWithoutRoleAndWithoutLogin() {
        var tenant = factory.createCompany();
        var user = factory.createUser("review-" + UUID.randomUUID() + "@example.de");
        factory.createMembership(tenant, user);

        AgentContext context = new AgentContext(tenant.getId(), null, "manual", NOW,
                Map.of("includeInvitedUsers", "false", "requireMfa", "true"));
        AgentResult result = factory.inTenant(tenant.getId(), () -> accessReview.run(context));

        assertThat(result.ok()).isTrue();
        assertThat(result.summary()).contains("Auffälligkeit");
        assertThat(result.produced()).extracting(p -> p.kind()).contains("access-finding");
        assertThat(result.meta().get("findings")).isNotEqualTo("0");
    }

    @Test
    void feeAuditFindsAndGroupsABankCharge() {
        var tenant = factory.createCompany();
        factory.inTenant(tenant.getId(), () -> {
            jdbc.update("""
                    insert into bank_transactions (id, tenant_id, booking_date, amount,
                                                   counterparty_name, purpose)
                    values (?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), tenant.getId(), java.sql.Date.valueOf(TODAY.minusDays(30)),
                    new BigDecimal("-12.50"), "Sparkasse", "Kontoführung Quartal");
            return null;
        });

        AgentContext context = new AgentContext(tenant.getId(), null, "manual", NOW,
                Map.of("periodFrom", TODAY.minusDays(60).toString(),
                        "periodTo", TODAY.toString()));
        AgentResult result = factory.inTenant(tenant.getId(), () -> feeAudit.run(context));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("groups", "1")
                .containsEntry("totalAmount", "12.50");
    }

    @Test
    void deliveryTrackingReportsAnOverduePurchaseOrder() {
        var tenant = factory.createCompany();
        factory.inTenant(tenant.getId(), () -> {
            jdbc.update("""
                    insert into purchase_orders (id, tenant_id, po_number, order_date,
                                                 expected_delivery_date, status, gross_amount)
                    values (?, ?, ?, ?, ?, 'OPEN', 250.00)
                    """, UUID.randomUUID(), tenant.getId(), "PO-" + UUID.randomUUID().toString().substring(0, 8),
                    java.sql.Date.valueOf(TODAY.minusDays(10)),
                    java.sql.Date.valueOf(TODAY.minusDays(6)));
            return null;
        });

        AgentContext context = new AgentContext(tenant.getId(), null, "manual", NOW,
                Map.of("horizonDays", "14", "minDelayDays", "1"));
        AgentResult result = factory.inTenant(tenant.getId(), () -> deliveryTracking.run(context));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("overdue", "1");
        assertThat(result.produced()).extracting(p -> p.kind()).contains("purchase-order");
    }
}
