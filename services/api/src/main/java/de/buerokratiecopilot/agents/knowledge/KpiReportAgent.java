package de.buerokratiecopilot.agents.knowledge;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.reports.ReportService;

/**
 * {@code knowledge-kpi-report} — KPI-Bericht.
 *
 * <p>Builds a report from the reporting module, which is the only place that knows what a metric
 * means and which tables it comes from. The agent adds the period logic and names every figure with
 * its value, unit and origin; it never invents a metric that the report module does not compute.</p>
 *
 * <p>Default period is the last <em>complete</em> month: a monthly report that includes today is a
 * figure that changes while it is read.</p>
 */
@Component
public class KpiReportAgent implements Agent {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final int MAX_PRODUCED = 30;

    private final ReportService reports;

    public KpiReportAgent(ReportService reports) {
        this.reports = reports;
    }

    @Override
    public String id() {
        return "knowledge-kpi-report";
    }

    @Override
    public AgentResult run(AgentContext context) {
        LocalDate today = LocalDate.ofInstant(context.now(), BERLIN);
        ReportService.ReportPeriod period;
        try {
            period = resolvePeriod(today, context.input("periodFrom"), context.input("periodTo"));
        } catch (DateTimeParseException | IllegalArgumentException e) {
            return AgentResult.failed("Der Zeitraum ist ungültig: " + e.getMessage());
        }
        ReportService.Audience audience = parseAudience(context.input("audience"));
        UUID workspaceId = null;
        if (context.input("workspaceId") != null && !context.input("workspaceId").isBlank()) {
            try {
                workspaceId = UUID.fromString(context.input("workspaceId").strip());
            } catch (IllegalArgumentException e) {
                return AgentResult.failed("Die Workspace-ID ist keine gültige UUID.");
            }
        }

        ReportService.Report report = reports.build(context.tenantId(), audience, period, workspaceId);
        List<ReportService.Metric> metrics = filter(report.metrics(), context.input("metricCodes"));
        if (metrics.isEmpty()) {
            return AgentResult.skipped("Für den gewählten Zeitraum und die gewählten Kennzahlen "
                    + "liefert das Berichtsmodul keine Werte.");
        }

        for (ReportService.Metric metric : metrics.stream().limit(MAX_PRODUCED).toList()) {
            context.addProduced("kpi-metric", metric.code(),
                    metric.label() + ": " + metric.value() + (metric.unit() == null ? ""
                            : " " + metric.unit()) + (metric.exact() ? "" : " (ungefähr)"));
        }

        long exact = metrics.stream().filter(ReportService.Metric::exact).count();
        long approximate = metrics.size() - exact;
        String summary = "KPI-Bericht für " + period.describe() + " mit " + metrics.size()
                + " Kennzahlen (" + exact + " exakt, " + approximate + " ungefähr).";
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("audience", audience.name());
        meta.put("periodFrom", period.from().toString());
        meta.put("periodTo", period.to().toString());
        meta.put("metrics", String.valueOf(metrics.size()));
        meta.put("exactMetrics", String.valueOf(exact));
        meta.put("approximateMetrics", String.valueOf(approximate));
        meta.put("workspaceId", workspaceId == null ? "" : workspaceId.toString());
        return AgentResult.done(summary, context.produced(), meta);
    }

    /**
     * Resolves the reporting period. Explicit dates win; a missing bound is completed to the
     * calendar month; a completely missing pair is the last complete month. Visible for tests.
     */
    public static ReportService.ReportPeriod resolvePeriod(LocalDate today, String fromRaw,
                                                           String toRaw) {
        LocalDate from = parseDate(fromRaw);
        LocalDate to = parseDate(toRaw);
        if (from == null && to == null) {
            LocalDate lastMonth = today.minusMonths(1);
            return new ReportService.ReportPeriod(lastMonth.withDayOfMonth(1),
                    lastMonth.withDayOfMonth(lastMonth.lengthOfMonth()));
        }
        if (from == null) {
            from = to.withDayOfMonth(1);
        }
        if (to == null) {
            to = from.withDayOfMonth(from.lengthOfMonth());
        }
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("Das Ende liegt vor dem Anfang");
        }
        return new ReportService.ReportPeriod(from, to);
    }

    static ReportService.Audience parseAudience(String raw) {
        if (raw == null || raw.isBlank()) {
            return ReportService.Audience.COMPANY;
        }
        try {
            return ReportService.Audience.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ReportService.Audience.COMPANY;
        }
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return LocalDate.parse(raw.strip());
    }

    private static List<ReportService.Metric> filter(List<ReportService.Metric> metrics, String rawCodes) {
        if (rawCodes == null || rawCodes.isBlank()) {
            return metrics;
        }
        List<String> codes = new ArrayList<>();
        for (String code : rawCodes.split(",")) {
            if (!code.isBlank()) {
                codes.add(code.strip().toUpperCase(Locale.ROOT));
            }
        }
        if (codes.isEmpty()) {
            return metrics;
        }
        return metrics.stream().filter(metric -> codes.contains(metric.code())).toList();
    }
}
