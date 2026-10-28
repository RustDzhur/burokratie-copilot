package de.buerokratiecopilot.reports;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.reports.ReportService.Audience;
import de.buerokratiecopilot.reports.ReportService.Report;
import de.buerokratiecopilot.reports.ReportService.ReportPeriod;

/**
 * Verifies that one tenant's data never appears in another's report (spec §26, D-008 acceptance).
 *
 * <p>The specification forbids using one client's data to compare with others, and the acceptance
 * criterion says the data of different tenants is not mixed. The test builds two tenants with figures
 * that would be obvious in each other's report — one with money and overdue work, one empty — and checks
 * that neither sees the other. Row-level security is the authority here, so this also verifies that the
 * reporting queries run inside a tenant context at all: a query without one sees nothing, which is how
 * the earlier modules failed before the context was bound.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class ReportIsolationIT {

    @Autowired
    private ReportService reports;

    @Autowired
    private JdbcTemplate jdbc;

    private static final ReportPeriod MARCH = new ReportPeriod(LocalDate.of(2026, 3, 1),
            LocalDate.of(2026, 3, 31));

    private UUID tenant(String slug) {
        return jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, ?, 'COMPANY') returning id
                """, UUID.class, slug, "Mandant " + slug);
    }

    private void invoice(UUID tenantId, String direction, String gross, String status) {
        jdbc.update("""
                insert into invoices (id, tenant_id, direction, invoice_type, invoice_number, currency,
                                      net_amount, gross_amount, paid_amount, status, payment_status,
                                      source, validation_state, validation_flags, bank_details_changed,
                                      metadata, issue_date)
                values (gen_random_uuid(), ?, ?, 'INVOICE', ?, 'EUR', ?::numeric, ?::numeric, 0,
                        ?, 'UNPAID', 'MANUAL', '{}'::jsonb, '[]'::jsonb, false, '{}'::jsonb,
                        '2026-03-10'::date)
                """, tenantId, direction, "RE-" + UUID.randomUUID().toString().substring(0, 8), gross,
                gross, status);
    }

    private void task(UUID tenantId, String status, String dueDate) {
        jdbc.update("""
                insert into tasks (id, tenant_id, title, status, priority, source, due_at, metadata)
                values (gen_random_uuid(), ?, 'Aufgabe', ?, 'NORMAL', 'MANUAL', ?::date, '{}'::jsonb)
                """, tenantId, status, dueDate);
    }

    private Report reportFor(UUID tenantId, Audience audience) {
        // Reports are read in a tenant context, exactly as the API does it.
        return de.buerokratiecopilot.shared.context.RequestContext.runAs(tenantId,
                () -> reports.build(tenantId, audience, MARCH, null));
    }

    @Test
    @DisplayName("one tenant's invoices do not appear in another tenant's report")
    void moneyDoesNotLeakBetweenTenants() {
        UUID rich = tenant("iso-rich-" + UUID.randomUUID().toString().substring(0, 6));
        UUID empty = tenant("iso-empty-" + UUID.randomUUID().toString().substring(0, 6));
        invoice(rich, "INCOMING", "1190.00", "APPROVED");
        invoice(rich, "OUTGOING", "500.00", "APPROVED");

        Report richReport = reportFor(rich, Audience.COMPANY);
        Report emptyReport = reportFor(empty, Audience.COMPANY);

        assertThat(richReport.metric("EXPENSES").value()).isEqualByComparingTo("1190.00");
        assertThat(richReport.metric("OUTSTANDING_RECEIVABLES").value()).isEqualByComparingTo("500.00");

        assertThat(emptyReport.metric("EXPENSES").value())
                .as("the other tenant's invoice must not be counted here")
                .isEqualByComparingTo("0");
        assertThat(emptyReport.metric("OUTSTANDING_RECEIVABLES").value()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("one tenant's overdue tasks do not appear in another tenant's report")
    void tasksDoNotLeakBetweenTenants() {
        UUID busy = tenant("iso-busy-" + UUID.randomUUID().toString().substring(0, 6));
        UUID quiet = tenant("iso-quiet-" + UUID.randomUUID().toString().substring(0, 6));
        task(busy, "NEW", "2026-03-01");
        task(busy, "IN_PROGRESS", "2026-03-15");

        assertThat(reportFor(busy, Audience.COMPANY).metric("OVERDUE_TASKS").value())
                .isEqualByComparingTo("2");
        assertThat(reportFor(quiet, Audience.COMPANY).metric("OVERDUE_TASKS").value())
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("two tenants with the same figures get their own, identical numbers")
    void identicalTenantsAreCountedSeparately() {
        UUID first = tenant("iso-a-" + UUID.randomUUID().toString().substring(0, 6));
        UUID second = tenant("iso-b-" + UUID.randomUUID().toString().substring(0, 6));
        invoice(first, "INCOMING", "100.00", "APPROVED");
        invoice(second, "INCOMING", "100.00", "APPROVED");

        // Each gets 100, not 200: the figures are equal, and they are still counted apart.
        assertThat(reportFor(first, Audience.COMPANY).metric("EXPENSES").value())
                .isEqualByComparingTo("100.00");
        assertThat(reportFor(second, Audience.COMPANY).metric("EXPENSES").value())
                .isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("there is no parameter through which one tenant could ask for another's figures")
    void noParameterSelectsAnotherTenant() {
        UUID owner = tenant("iso-owner-" + UUID.randomUUID().toString().substring(0, 6));
        UUID stranger = tenant("iso-stranger-" + UUID.randomUUID().toString().substring(0, 6));
        invoice(owner, "INCOMING", "777.00", "APPROVED");

        // The stranger asks for a report in its own context but names the owner's identifier: row-level
        // security returns nothing, which is the answer that keeps the promise.
        Report strangerReport = de.buerokratiecopilot.shared.context.RequestContext.runAs(stranger,
                () -> reports.build(owner, Audience.COMPANY, MARCH, null));

        assertThat(strangerReport.metric("EXPENSES").value()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("the same tenant sees its own figures consistently across audiences")
    void aTenantSeesItsOwnFigures() {
        UUID tenantId = tenant("iso-self-" + UUID.randomUUID().toString().substring(0, 6));
        invoice(tenantId, "INCOMING", "250.00", "APPROVED");

        Report individual = reportFor(tenantId, Audience.INDIVIDUAL);
        Report company = reportFor(tenantId, Audience.COMPANY);

        // Expenses is one of the few metrics both audiences have, and it must agree with itself.
        assertThat(individual.metric("EXPENSES").value())
                .isEqualByComparingTo(company.metric("EXPENSES").value());
    }
}
