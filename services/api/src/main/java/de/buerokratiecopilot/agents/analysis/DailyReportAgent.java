package de.buerokratiecopilot.agents.analysis;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.reports.ReportService;

/**
 * {@code report-daily} — Tagesberichte.
 *
 * <p>Builds the summary of one Berlin calendar day: documents and tasks that arrived, deadlines
 * that fall on the day, invoices due, executed payments and the day's bank movements. The
 * figures come straight from the platform's own tables (documents, tasks, invoices, payments,
 * bank_transactions), plus period context (expenses of the last 30 days, open receivables for
 * company tenants) from the reports module.</p>
 *
 * <p>Idempotent by construction: the run is read-only, nothing is persisted, and the produced
 * ref {@code daily:&lt;date&gt;} is deterministic, so a repeated run never duplicates anything.
 * A day without any activity is reported honestly as {@code ok=false}, not as an empty
 * success.</p>
 */
@Component
public class DailyReportAgent implements Agent {

    private static final int CONTEXT_DAYS = 30;

    private final JdbcTemplate jdbc;
    private final ReportService reports;

    public DailyReportAgent(JdbcTemplate jdbc, ReportService reports) {
        this.jdbc = jdbc;
        this.reports = reports;
    }

    @Override
    public String id() {
        return "report-daily";
    }

    @Override
    @Transactional(readOnly = true)
    public AgentResult run(AgentContext context) {
        LocalDate day = AnalysisDates.parseDay(context.input("date"), AnalysisDates.day(context.now()));
        DailyFigures figures = collectDay(context.tenantId(), day);
        Map<String, String> reportContext = reportContext(context.tenantId(), day);
        return buildResult(figures, reportContext, day);
    }

    /** Everything the day brought; all counts and amounts are tenant-scoped. */
    record DailyFigures(long documents, long tasks, long deadlines, long overdueTasks,
                        long invoicesDue, long payments, BigDecimal paymentsAmount,
                        long bankTransactions, BigDecimal bankIncoming, BigDecimal bankOutgoing) {

        boolean any() {
            return documents > 0 || tasks > 0 || deadlines > 0 || overdueTasks > 0
                    || invoicesDue > 0 || payments > 0 || bankTransactions > 0;
        }
    }

    /** The day's figures from the modules' tables. */
    DailyFigures collectDay(UUID tenantId, LocalDate day) {
        java.sql.Timestamp start = java.sql.Timestamp.from(AnalysisDates.startOfDay(day));
        java.sql.Timestamp end = java.sql.Timestamp.from(AnalysisDates.endOfDayExclusive(day));
        long documents = count("""
                select count(*) from documents
                where tenant_id = ? and deleted_at is null and created_at >= ? and created_at < ?
                """, tenantId, start, end);
        long tasks = count("""
                select count(*) from tasks
                where tenant_id = ? and deleted_at is null and created_at >= ? and created_at < ?
                """, tenantId, start, end);
        long deadlines = count("""
                select count(*) from tasks
                where tenant_id = ? and deleted_at is null and status not in ('DONE', 'CANCELLED')
                  and due_at >= ? and due_at < ?
                """, tenantId, start, end);
        long overdue = count("""
                select count(*) from tasks
                where tenant_id = ? and deleted_at is null and status not in ('DONE', 'CANCELLED')
                  and due_at < ?
                """, tenantId, end);
        long invoicesDue = count("""
                select count(*) from invoices
                where tenant_id = ? and deleted_at is null and due_date = ?
                  and payment_status <> 'PAID'
                  and status not in ('DRAFT', 'CANCELLED', 'CREDITED', 'REJECTED', 'PAID')
                """, tenantId, day);
        long payments = count("""
                select count(*) from payments
                where tenant_id = ? and deleted_at is null
                  and status in ('EXECUTED', 'SETTLED') and payment_date = ?
                """, tenantId, day);
        BigDecimal paymentsAmount = amount("""
                select coalesce(sum(amount), 0) from payments
                where tenant_id = ? and deleted_at is null
                  and status in ('EXECUTED', 'SETTLED') and payment_date = ?
                """, tenantId, day);
        long bankTransactions = count("""
                select count(*) from bank_transactions
                where tenant_id = ? and booking_date = ?
                """, tenantId, day);
        BigDecimal bankIncoming = amount("""
                select coalesce(sum(amount), 0) from bank_transactions
                where tenant_id = ? and booking_date = ? and amount > 0
                """, tenantId, day);
        BigDecimal bankOutgoing = amount("""
                select coalesce(sum(-amount), 0) from bank_transactions
                where tenant_id = ? and booking_date = ? and amount < 0
                """, tenantId, day);
        return new DailyFigures(documents, tasks, deadlines, overdue, invoicesDue, payments,
                paymentsAmount, bankTransactions, bankIncoming, bankOutgoing);
    }

    /**
     * Period context from the reports module: expenses of the last {@value #CONTEXT_DAYS} days
     * (every audience) and open receivables (company tenants). Keys that do not exist for the
     * tenant's audience are simply absent, never zero.
     */
    Map<String, String> reportContext(UUID tenantId, LocalDate day) {
        Map<String, String> context = new LinkedHashMap<>();
        ReportService.Audience audience = audienceOf(tenantId);
        ReportService.Report report = reports.build(tenantId, audience,
                new ReportService.ReportPeriod(day.minusDays(CONTEXT_DAYS - 1L), day), null);
        for (ReportService.Metric metric : report.metrics()) {
            if (metric.code().equals("EXPENSES")) {
                context.put("expenses30d", money(metric.value()));
            } else if (metric.code().equals("OUTSTANDING_RECEIVABLES")) {
                context.put("receivables", money(metric.value()));
            }
        }
        return context;
    }

    /**
     * Assembles the run result from collected figures — pure, so the arithmetic of the report
     * (what counts as "a day with activity", what goes into the summary and the meta) is
     * unit-testable without a database.
     */
    AgentResult buildResult(DailyFigures figures, Map<String, String> reportContext, LocalDate day) {
        if (!figures.any()) {
            return AgentResult.skipped("Keine Aktivität am " + day
                    + ": keine neuen Dokumente oder Aufgaben, keine Fristen und keine Zahlungen.");
        }
        String summary = "Tagesbericht " + day + ": " + figures.documents() + " neue Dokumente, "
                + figures.tasks() + " neue Aufgaben, " + figures.deadlines() + " Fristen heute, "
                + figures.payments() + " Zahlungen über " + money(figures.paymentsAmount()) + " EUR, "
                + figures.bankTransactions() + " Kontobewegungen ("
                + money(figures.bankIncoming()) + " EUR rein, "
                + money(figures.bankOutgoing()) + " EUR raus).";
        if (reportContext.containsKey("expenses30d")) {
            summary += " Ausgaben der letzten " + CONTEXT_DAYS + " Tage: "
                    + reportContext.get("expenses30d") + " EUR.";
        }
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("date", day.toString());
        meta.put("documents", Long.toString(figures.documents()));
        meta.put("tasks", Long.toString(figures.tasks()));
        meta.put("deadlines", Long.toString(figures.deadlines()));
        meta.put("overdueTasks", Long.toString(figures.overdueTasks()));
        meta.put("invoicesDue", Long.toString(figures.invoicesDue()));
        meta.put("payments", Long.toString(figures.payments()));
        meta.put("paymentsAmount", money(figures.paymentsAmount()));
        meta.put("bankTransactions", Long.toString(figures.bankTransactions()));
        meta.put("bankIncoming", money(figures.bankIncoming()));
        meta.put("bankOutgoing", money(figures.bankOutgoing()));
        meta.putAll(reportContext);
        return AgentResult.done(summary,
                List.of(new AgentResult.Produced("report", "daily:" + day, "Tagesbericht für " + day)),
                meta);
    }

    /** Company tenants get the company report, private households the individual one. */
    private ReportService.Audience audienceOf(UUID tenantId) {
        String accountType = jdbc.queryForObject(
                "select account_type from tenants where id = ?", String.class, tenantId);
        return switch (accountType == null ? "" : accountType) {
            case "PRIVATE_PERSON", "FAMILY_HOUSEHOLD" -> ReportService.Audience.INDIVIDUAL;
            default -> ReportService.Audience.COMPANY;
        };
    }

    private long count(String sql, Object... arguments) {
        Long value = jdbc.queryForObject(sql, Long.class, arguments);
        return value == null ? 0 : value;
    }

    private BigDecimal amount(String sql, Object... arguments) {
        BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class, arguments);
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String money(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }
}
