package de.buerokratiecopilot.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.reports.ReportService.Audience;
import de.buerokratiecopilot.reports.ReportService.Metric;
import de.buerokratiecopilot.reports.ReportService.Report;
import de.buerokratiecopilot.reports.ReportService.ReportPeriod;
import de.buerokratiecopilot.shared.error.ApiException;

/**
 * Verifies that reported figures are right (spec §26, D-008 acceptance).
 *
 * <p>Two things are checked beyond the arithmetic, because the specification asks for them explicitly:
 * every metric carries its period and its filters, and an approximate value says that it is one. A
 * report that shows a number without those is not wrong in a way a test could catch elsewhere — it is
 * wrong in the way that matters, by looking trustworthy.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class ReportAccuracyIT {

    @Autowired
    private ReportService reports;

    @Autowired
    private JdbcTemplate jdbc;

    private static final ReportPeriod MARCH = new ReportPeriod(LocalDate.of(2026, 3, 1),
            LocalDate.of(2026, 3, 31));

    private UUID tenant() {
        return jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'Bericht Test', 'COMPANY') returning id
                """, UUID.class, "rep-" + UUID.randomUUID().toString().substring(0, 12));
    }

    private void invoice(UUID tenantId, String direction, String gross, String paid, String issueDate,
                         String status) {
        jdbc.update("""
                insert into invoices (id, tenant_id, direction, invoice_type, invoice_number, currency,
                                      net_amount, gross_amount, paid_amount, status, payment_status,
                                      source, validation_state, validation_flags, bank_details_changed,
                                      metadata, issue_date)
                values (gen_random_uuid(), ?, ?, 'INVOICE', ?, 'EUR', ?::numeric, ?::numeric,
                        ?::numeric, ?, 'UNPAID', 'MANUAL', '{}'::jsonb, '[]'::jsonb, false,
                        '{}'::jsonb, ?::date)
                """, tenantId, direction, "RE-" + UUID.randomUUID().toString().substring(0, 8), gross,
                gross, paid, status, issueDate);
    }

    private void task(UUID tenantId, String status, String dueDate, UUID assignee) {
        jdbc.update("""
                insert into tasks (id, tenant_id, title, status, priority, source, due_at,
                                   completed_at, metadata)
                values (gen_random_uuid(), ?, 'Aufgabe', ?, 'NORMAL', 'MANUAL', ?::date,
                        case when ? = 'DONE' then now() end, '{}'::jsonb)
                """, tenantId, status, dueDate, status);
        if (assignee != null) {
            jdbc.update("""
                    insert into task_assignees (id, tenant_id, task_id, user_id, assigned_at)
                    select gen_random_uuid(), ?, id, ?, now() from tasks
                    where tenant_id = ? and title = 'Aufgabe' and due_at = ?::date
                    """, tenantId, assignee, tenantId, dueDate);
        }
    }

    private UUID person() {
        return jdbc.queryForObject("""
                insert into users (id, email, status, display_name, locale, time_zone,
                                   failed_login_attempts, must_change_password)
                values (gen_random_uuid(), ?, 'ACTIVE', 'Sachbearbeiter', 'de', 'Europe/Berlin', 0, false)
                returning id
                """, UUID.class, "rp-" + UUID.randomUUID().toString().substring(0, 10) + "@example.de");
    }

    @Test
    @DisplayName("the expenses of a period are the sum of the incoming invoices in it")
    void expensesMatchTheInvoicesOfThePeriod() {
        UUID tenantId = tenant();
        invoice(tenantId, "INCOMING", "119.00", "0", "2026-03-05", "APPROVED");
        invoice(tenantId, "INCOMING", "238.00", "0", "2026-03-20", "APPROVED");
        // Outside the period, and therefore not part of the answer.
        invoice(tenantId, "INCOMING", "500.00", "0", "2026-04-02", "APPROVED");
        // Cancelled, and therefore not part of it either.
        invoice(tenantId, "INCOMING", "1000.00", "0", "2026-03-10", "CANCELLED");

        Report report = reports.build(tenantId, Audience.COMPANY, MARCH, null);

        assertThat(report.metric("EXPENSES").value()).isEqualByComparingTo("357.00");
    }

    @Test
    @DisplayName("outstanding receivables subtract what has already been paid")
    void receivablesAreNetOfPayments() {
        UUID tenantId = tenant();
        invoice(tenantId, "OUTGOING", "119.00", "0", "2026-03-05", "APPROVED");
        invoice(tenantId, "OUTGOING", "238.00", "38.00", "2026-03-06", "PARTIALLY_PAID");
        // Fully paid: nothing outstanding.
        invoice(tenantId, "OUTGOING", "500.00", "500.00", "2026-03-07", "PAID");
        // An incoming invoice is not a receivable.
        invoice(tenantId, "INCOMING", "999.00", "0", "2026-03-08", "APPROVED");

        Report report = reports.build(tenantId, Audience.COMPANY, MARCH, null);

        assertThat(report.metric("OUTSTANDING_RECEIVABLES").value()).isEqualByComparingTo("319.00");
    }

    @Test
    @DisplayName("overdue tasks are counted as of the end of the period, not as of today")
    void overdueTasksUseTheEndOfThePeriod() {
        UUID tenantId = tenant();
        task(tenantId, "NEW", "2026-03-10", null);
        task(tenantId, "IN_PROGRESS", "2026-03-31", null);
        task(tenantId, "DONE", "2026-03-01", null);
        task(tenantId, "NEW", "2026-05-15", null);

        Report march = reports.build(tenantId, Audience.COMPANY, MARCH, null);
        Report april = reports.build(tenantId, Audience.COMPANY,
                new ReportPeriod(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30)), null);

        assertThat(march.metric("OVERDUE_TASKS").value())
                .as("one task was overdue by 31 March; the done one is not overdue "
                        + "and the May one is not yet")
                .isEqualByComparingTo("1");
        assertThat(april.metric("OVERDUE_TASKS").value()).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("every metric carries its period and its filters")
    void everyMetricCarriesItsContext() {
        UUID tenantId = tenant();

        Report report = reports.build(tenantId, Audience.COMPANY, MARCH, null);

        assertThat(report.metrics()).isNotEmpty();
        assertThat(report.metrics()).allSatisfy(metric -> {
            assertThat(metric.period())
                    .as("a number without a period is not a metric")
                    .isEqualTo(MARCH);
            assertThat(metric.filters()).containsKey("workspaceId");
            assertThat(metric.origin())
                    .as("the specification asks where a metric came from")
                    .isNotBlank();
            assertThat(metric.label()).isNotBlank();
            assertThat(metric.unit()).isNotBlank();
        });
    }

    @Test
    @DisplayName("an approximate value says so, and an exact one is not marked")
    void approximateValuesAreLabelled() {
        UUID tenantId = tenant();

        Report report = reports.build(tenantId, Audience.COMPANY, MARCH, null);

        Metric processingTime = report.metric("PROCESSING_TIME_AVERAGE");
        assertThat(processingTime.exact()).isFalse();
        assertThat(processingTime.approximationNote())
                .as("the note has to say why, not merely that it is approximate")
                .contains("Zeitstempeln");

        Metric expenses = report.metric("EXPENSES");
        assertThat(expenses.exact())
                .as("recorded amounts are exact arithmetic and must not be marked as a guess")
                .isTrue();
        assertThat(expenses.approximationNote()).isNull();
    }

    @Test
    @DisplayName("a metric that is not exact never claims to be a financial figure")
    void approximateMetricsAreNotFinancialClaims() {
        UUID tenantId = tenant();

        Report report = reports.build(tenantId, Audience.COMPANY, MARCH, null);

        assertThat(report.metrics().stream().filter(metric -> !metric.exact()).toList())
                .allSatisfy(metric -> assertThat(metric.unit())
                        .as("an approximate value must not be presented in a currency")
                        .isNotEqualTo("EUR"));
    }

    @Test
    @DisplayName("the report says what it does not do: no comparison between tenants")
    void theReportStatesItsScope() {
        UUID tenantId = tenant();

        Report report = reports.build(tenantId, Audience.COMPANY, MARCH, null);

        assertThat(report.notes()).anySatisfy(note -> assertThat(note).contains("Vergleich"));
        assertThat(report.filters()).containsKey("workspace");
    }

    @Test
    @DisplayName("a report without a period is refused rather than silently defaulted")
    void periodIsRequired() {
        UUID tenantId = tenant();

        assertThatThrownBy(() -> reports.build(tenantId, Audience.COMPANY,
                new ReportPeriod(null, LocalDate.of(2026, 3, 31)), null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Zeitraum");
        assertThatThrownBy(() -> reports.build(tenantId, Audience.COMPANY,
                new ReportPeriod(LocalDate.of(2026, 3, 31), LocalDate.of(2026, 3, 1)), null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Ende");
    }

    @Test
    @DisplayName("the two audiences get different metrics, as the specification lists them")
    void audiencesDiffer() {
        UUID tenantId = tenant();

        Report individual = reports.build(tenantId, Audience.INDIVIDUAL, MARCH, null);
        Report company = reports.build(tenantId, Audience.COMPANY, MARCH, null);

        assertThat(individual.metricCodes()).isNotEqualTo(company.metricCodes());
        assertThat(individual.metric("UNFINISHED_CASES")).isNotNull();
        assertThat(company.metric("AWAITING_MANUAL_REVIEW")).isNotNull();
        // A private individual does not have receivables, and a company report does not list personal
        // deadlines; the specification lists them separately for that reason.
        assertThat(individual.metrics()).extracting(Metric::code)
                .doesNotContain("OUTSTANDING_RECEIVABLES");
        assertThat(company.metrics()).extracting(Metric::code).doesNotContain("AWAITING_REPLY");
    }

    @Test
    @DisplayName("the report renders every value together with its context")
    void theRenderedReportCarriesEverything() {
        UUID tenantId = tenant();

        var rendered = reports.build(tenantId, Audience.COMPANY, MARCH, null).asMap();

        assertThat(rendered).containsKeys("audience", "period", "filters", "generatedAt", "metrics",
                "notes");
        assertThat((java.util.List<?>) rendered.get("metrics")).isNotEmpty();
        assertThat(rendered.get("period")).isEqualTo(MARCH.asMap());
    }

    @Test
    @DisplayName("an empty tenant reports zero rather than failing")
    void emptyTenantReportsZero() {
        UUID tenantId = tenant();

        Report report = reports.build(tenantId, Audience.COMPANY, MARCH, null);

        assertThat(report.metric("EXPENSES").value()).isEqualByComparingTo("0");
        assertThat(report.metric("OUTSTANDING_RECEIVABLES").value()).isEqualByComparingTo("0");
        assertThat(report.metrics()).allSatisfy(metric ->
                assertThat(metric.value()).isNotNull());
    }

    @Test
    @DisplayName("a measured average is over the tasks that were completed in the period")
    void processingTimeUsesCompletedTasks() {
        UUID tenantId = tenant();
        // A task created and completed inside the period.
        jdbc.update("""
                insert into tasks (id, tenant_id, title, status, priority, source, created_at,
                                   completed_at, metadata)
                values (gen_random_uuid(), ?, 'Schnell', 'DONE', 'NORMAL', 'MANUAL',
                        '2026-03-01T08:00:00Z'::timestamptz, '2026-03-01T12:00:00Z'::timestamptz,
                        '{}'::jsonb)
                """, tenantId);

        Report report = reports.build(tenantId, Audience.COMPANY, MARCH, null);

        assertThat(report.metric("PROCESSING_TIME_AVERAGE").value())
                .isCloseTo(new BigDecimal("4"), org.assertj.core.data.Offset.offset(new BigDecimal("0.5")));
    }
}
