package de.buerokratiecopilot.agents.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.reports.ReportService;

/** Verifies the period logic of {@code knowledge-kpi-report}. */
class KpiReportAgentTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 26);

    @Test
    void theDefaultPeriodIsTheLastCompleteMonth() {
        ReportService.ReportPeriod period = KpiReportAgent.resolvePeriod(TODAY, null, null);

        assertThat(period.from()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(period.to()).isEqualTo(LocalDate.of(2026, 9, 30));
    }

    @Test
    void aMissingBoundIsCompletedToTheCalendarMonth() {
        ReportService.ReportPeriod fromOnly = KpiReportAgent.resolvePeriod(TODAY,
                "2026-02-10", null);
        assertThat(fromOnly.from()).isEqualTo(LocalDate.of(2026, 2, 10));
        assertThat(fromOnly.to()).isEqualTo(LocalDate.of(2026, 2, 28));

        ReportService.ReportPeriod toOnly = KpiReportAgent.resolvePeriod(TODAY, null, "2026-02-28");
        assertThat(toOnly.from()).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(toOnly.to()).isEqualTo(LocalDate.of(2026, 2, 28));
    }

    @Test
    void explicitBoundsAreKept() {
        ReportService.ReportPeriod period = KpiReportAgent.resolvePeriod(TODAY, "2026-01-01",
                "2026-03-31");

        assertThat(period.from()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(period.to()).isEqualTo(LocalDate.of(2026, 3, 31));
    }

    @Test
    void anInvertedPeriodIsRejected() {
        assertThatThrownBy(() -> KpiReportAgent.resolvePeriod(TODAY, "2026-05-01", "2026-04-01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Ende");
    }

    @Test
    void audienceDefaultsToCompany() {
        assertThat(KpiReportAgent.parseAudience(null)).isEqualTo(ReportService.Audience.COMPANY);
        assertThat(KpiReportAgent.parseAudience("individual"))
                .isEqualTo(ReportService.Audience.INDIVIDUAL);
    }
}
