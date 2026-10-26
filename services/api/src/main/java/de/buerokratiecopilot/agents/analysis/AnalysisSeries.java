package de.buerokratiecopilot.agents.analysis;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Daily series over the platform's own tables — the raw material of the anomaly and forecast
 * agents. One series = one Berlin calendar day per row with the value measured on it.
 *
 * <p>Every query is scoped to the caller's tenant by an explicit {@code tenant_id} filter;
 * the module services and row-level security stay the single source of truth for what the
 * tenant may see.</p>
 */
final class AnalysisSeries {

    /** One Berlin calendar day and the value the series measured on it. */
    record Point(LocalDate day, double value) {
    }

    /** A named series: which daily figures it contains, for reports and produced refs. */
    record Series(String name, List<Point> points) {
    }

    private static final RowMapper<Point> POINT_MAPPER = (rs, row) -> new Point(
            rs.getObject("day", LocalDate.class),
            rs.getBigDecimal("value").doubleValue());

    private AnalysisSeries() {
    }

    /** Documents created per day. */
    static List<Point> documentsPerDay(JdbcTemplate jdbc, UUID tenantId, Instant from, Instant to) {
        return jdbc.query("""
                select (created_at at time zone 'Europe/Berlin')::date as day, count(*)::numeric as value
                from documents
                where tenant_id = ? and deleted_at is null and created_at >= ? and created_at < ?
                group by 1 order by 1
                """, POINT_MAPPER, tenantId, java.sql.Timestamp.from(from), java.sql.Timestamp.from(to));
    }

    /** Tasks created per day. */
    static List<Point> tasksPerDay(JdbcTemplate jdbc, UUID tenantId, Instant from, Instant to) {
        return jdbc.query("""
                select (created_at at time zone 'Europe/Berlin')::date as day, count(*)::numeric as value
                from tasks
                where tenant_id = ? and deleted_at is null and created_at >= ? and created_at < ?
                group by 1 order by 1
                """, POINT_MAPPER, tenantId, java.sql.Timestamp.from(from), java.sql.Timestamp.from(to));
    }

    /** Invoices issued per day (issue_date is a date column, compared as such). */
    static List<Point> invoicesPerDay(JdbcTemplate jdbc, UUID tenantId, LocalDate from, LocalDate to) {
        return jdbc.query("""
                select issue_date as day, count(*)::numeric as value
                from invoices
                where tenant_id = ? and deleted_at is null and issue_date between ? and ?
                group by 1 order by 1
                """, POINT_MAPPER, tenantId, from, to);
    }

    /** Executed payments per day: count of rows and paid volume. */
    static List<Point> paymentsPerDay(JdbcTemplate jdbc, UUID tenantId, LocalDate from, LocalDate to) {
        return jdbc.query("""
                select payment_date as day, coalesce(sum(amount), 0) as value
                from payments
                where tenant_id = ? and deleted_at is null
                  and status in ('EXECUTED', 'SETTLED') and payment_date between ? and ?
                group by 1 order by 1
                """, POINT_MAPPER, tenantId, from, to);
    }

    /** Net bank volume per booking day (amounts carry their sign). */
    static List<Point> bankVolumePerDay(JdbcTemplate jdbc, UUID tenantId, LocalDate from, LocalDate to) {
        return jdbc.query("""
                select booking_date as day, coalesce(sum(amount), 0) as value
                from bank_transactions
                where tenant_id = ? and booking_date between ? and ?
                group by 1 order by 1
                """, POINT_MAPPER, tenantId, from, to);
    }

    /**
     * All series the anomaly/forecast agents evaluate, restricted by the optional
     * {@code series} input ({@code documents|tasks|invoices|payments|bank}; empty = all).
     * The window is {@code [from, to]} in Berlin days, both inclusive.
     */
    static List<Series> collect(JdbcTemplate jdbc, UUID tenantId, String seriesFilter,
                                LocalDate from, LocalDate to) {
        Instant fromInstant = AnalysisDates.startOfDay(from);
        Instant toInstant = AnalysisDates.endOfDayExclusive(to);
        List<Series> series = new ArrayList<>();
        if (matches(seriesFilter, "documents")) {
            series.add(new Series("documents", documentsPerDay(jdbc, tenantId, fromInstant, toInstant)));
        }
        if (matches(seriesFilter, "tasks")) {
            series.add(new Series("tasks", tasksPerDay(jdbc, tenantId, fromInstant, toInstant)));
        }
        if (matches(seriesFilter, "invoices")) {
            series.add(new Series("invoices", invoicesPerDay(jdbc, tenantId, from, to)));
        }
        if (matches(seriesFilter, "payments")) {
            series.add(new Series("payments", paymentsPerDay(jdbc, tenantId, from, to)));
        }
        if (matches(seriesFilter, "bank")) {
            series.add(new Series("bank", bankVolumePerDay(jdbc, tenantId, from, to)));
        }
        return series;
    }

    private static boolean matches(String filter, String name) {
        return filter == null || filter.isBlank() || filter.equals(name);
    }

    /** {@code null}-safe decimal to double. */
    static double asDouble(BigDecimal value) {
        return value == null ? 0.0 : value.doubleValue();
    }
}
