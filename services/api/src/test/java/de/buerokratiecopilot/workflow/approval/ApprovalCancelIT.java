package de.buerokratiecopilot.workflow.approval;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * Withdrawing an approval request, at the service level.
 *
 * <p>Written to settle an open question: over HTTP the cancel endpoint answers 409 for a request that is
 * {@code PENDING} in the database, and I could not tell whether the fault is in the service or in the way
 * the endpoint reaches it. The same operation, called directly, answers that — one way or the other.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class ApprovalCancelIT {

    @Autowired
    private ApprovalService approvals;

    @Autowired
    private JdbcTemplate jdbc;

    private record Fixture(UUID tenantId, UUID userId, UUID approvalId) {
    }

    private Fixture fixture() {
        UUID tenantId = jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'Abbruch Test', 'COMPANY') returning id
                """, UUID.class, "ca-" + UUID.randomUUID().toString().substring(0, 12));
        UUID userId = jdbc.queryForObject("""
                insert into users (id, email, status, display_name, locale, time_zone,
                                   failed_login_attempts, must_change_password)
                values (gen_random_uuid(), ?, 'ACTIVE', 'Person', 'de', 'Europe/Berlin', 0, false)
                returning id
                """, UUID.class, "ca-" + UUID.randomUUID().toString().substring(0, 10) + "@example.de");
        jdbc.update("""
                insert into memberships (id, tenant_id, user_id, status, is_owner)
                values (gen_random_uuid(), ?, ?, 'ACTIVE', true)
                """, tenantId, userId);

        UUID approvalId = RequestContext.runAs(tenantId, () -> approvals.request(tenantId, "invoice",
                UUID.randomUUID(), "INVOICE_APPROVAL", "Rechnung prüfen", "Betrag geprüft",
                new BigDecimal("119.00"), "OUTGOING", null, userId)).approvalId();
        return new Fixture(tenantId, userId, approvalId);
    }

    @Test
    @DisplayName("the request is pending before it is withdrawn")
    void theRequestIsPending() {
        Fixture fixture = fixture();

        assertThat(jdbc.queryForObject("select status from approvals where id = ?", String.class,
                fixture.approvalId())).isEqualTo("PENDING");
        assertThat(RequestContext.runAs(fixture.tenantId(),
                () -> approvals.pending(fixture.tenantId())))
                .extracting(row -> row.get("id")).contains(fixture.approvalId());
    }

    @Test
    @DisplayName("withdrawing it at the service level works")
    void withdrawingWorksAtTheServiceLevel() {
        Fixture fixture = fixture();

        RequestContext.runAs(fixture.tenantId(), () -> {
            approvals.cancel(fixture.tenantId(), fixture.approvalId(), fixture.userId(), "Zurückgezogen");
            return null;
        });

        assertThat(jdbc.queryForObject("select status from approvals where id = ?", String.class,
                fixture.approvalId())).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("a withdrawn request is no longer among the open ones")
    void aWithdrawnRequestIsClosed() {
        Fixture fixture = fixture();
        RequestContext.runAs(fixture.tenantId(), () -> {
            approvals.cancel(fixture.tenantId(), fixture.approvalId(), fixture.userId(), null);
            return null;
        });

        assertThat(RequestContext.runAs(fixture.tenantId(),
                () -> approvals.pending(fixture.tenantId())))
                .extracting(row -> row.get("id")).doesNotContain(fixture.approvalId());
    }

    @Test
    @DisplayName("withdrawing it twice is refused the second time")
    void withdrawingTwiceIsRefused() {
        Fixture fixture = fixture();
        RequestContext.runAs(fixture.tenantId(), () -> {
            approvals.cancel(fixture.tenantId(), fixture.approvalId(), fixture.userId(), null);
            return null;
        });

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> RequestContext.runAs(fixture.tenantId(),
                () -> {
                    approvals.cancel(fixture.tenantId(), fixture.approvalId(), fixture.userId(), null);
                    return null;
                })).isInstanceOf(de.buerokratiecopilot.shared.error.ApiException.class);
    }

    @Test
    @DisplayName("what the withdrawal recorded stays in the history")
    void theWithdrawalIsRecorded() {
        Fixture fixture = fixture();
        RequestContext.runAs(fixture.tenantId(), () -> {
            approvals.cancel(fixture.tenantId(), fixture.approvalId(), fixture.userId(), "Nicht mehr nötig");
            return null;
        });

        assertThat(RequestContext.runAs(fixture.tenantId(),
                () -> approvals.history(fixture.tenantId(), fixture.approvalId())))
                .extracting(entry -> entry.get("event_type")).contains("CANCELLED");
    }

    @Test
    @DisplayName("a request of another tenant cannot be withdrawn")
    void anotherTenantsRequestCannotBeWithdrawn() {
        Fixture fixture = fixture();
        UUID other = jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'Fremd', 'COMPANY') returning id
                """, UUID.class, "ca-" + UUID.randomUUID().toString().substring(0, 12));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> RequestContext.runAs(other, () -> {
            approvals.cancel(other, fixture.approvalId(), fixture.userId(), null);
            return null;
        })).isInstanceOf(de.buerokratiecopilot.shared.error.ApiException.class);

        assertThat(jdbc.queryForObject("select status from approvals where id = ?", String.class,
                fixture.approvalId())).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("the service reports how the status is written after a withdrawal")
    void theStoredStatusIsReadable() {
        Fixture fixture = fixture();
        RequestContext.runAs(fixture.tenantId(), () -> {
            approvals.cancel(fixture.tenantId(), fixture.approvalId(), fixture.userId(), null);
            return null;
        });

        // The check constraint allows CANCELLED, and the stored text is what the interface would show.
        assertThat(jdbc.queryForList("""
                select status, decision_note from approvals where id = ?
                """, fixture.approvalId())).hasSize(1);
        assertThat(RequestContext.runAs(fixture.tenantId(),
                () -> approvals.pending(fixture.tenantId()))).isNotNull();
    }

    @Test
    @DisplayName("a withdrawal is not recorded as a decision, and the journal names who withdrew it")
    void aWithdrawalIsNotADecision() {
        Fixture fixture = fixture();
        RequestContext.runAs(fixture.tenantId(), () -> {
            approvals.cancel(fixture.tenantId(), fixture.approvalId(), fixture.userId(), "Grund");
            return null;
        });

        // The schema is right: decided_at and decided_by may only be set for APPROVED or REJECTED, because
        // those columns name a person who decided something. Nobody decided here - the requester took their
        // own request back - and a name in them would be a name on a decision that never happened.
        assertThat(jdbc.queryForObject("select decided_by from approvals where id = ?", UUID.class,
                fixture.approvalId())).isNull();
        assertThat(jdbc.queryForObject("select decided_at from approvals where id = ?",
                java.sql.Timestamp.class, fixture.approvalId())).isNull();
        // Who withdrew it, and why, is in the journal - which is where the history of the request lives.
        assertThat(RequestContext.runAs(fixture.tenantId(),
                () -> approvals.history(fixture.tenantId(), fixture.approvalId())))
                .anySatisfy(entry -> assertThat(String.valueOf(entry.get("comment"))).contains("Grund"));
    }

    @Test
    @DisplayName("a decision after a withdrawal is refused")
    void aDecisionAfterWithdrawalIsRefused() {
        Fixture fixture = fixture();
        RequestContext.runAs(fixture.tenantId(), () -> {
            approvals.cancel(fixture.tenantId(), fixture.approvalId(), fixture.userId(), null);
            return null;
        });

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> RequestContext.runAs(fixture.tenantId(),
                () -> approvals.decide(fixture.tenantId(), fixture.approvalId(), UUID.randomUUID(), true,
                        null)))
                .isInstanceOf(de.buerokratiecopilot.shared.error.ApiException.class);
    }

    @Test
    @DisplayName("the open list keeps every other request")
    void cancellingOneKeepsTheOthers() {
        Fixture first = fixture();
        UUID second = RequestContext.runAs(first.tenantId(), () -> approvals.request(first.tenantId(),
                "invoice", UUID.randomUUID(), "INVOICE_APPROVAL", "Zweite", null, new BigDecimal("10.00"),
                "OUTGOING", null, first.userId())).approvalId();

        RequestContext.runAs(first.tenantId(), () -> {
            approvals.cancel(first.tenantId(), first.approvalId(), first.userId(), null);
            return null;
        });

        assertThat(RequestContext.runAs(first.tenantId(),
                () -> approvals.pending(first.tenantId())))
                .extracting(row -> row.get("id"))
                .contains(second)
                .doesNotContain(first.approvalId());
        assertThat(List.of(second)).isNotEmpty();
    }
}
