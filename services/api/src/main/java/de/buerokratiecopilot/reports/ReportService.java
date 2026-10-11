package de.buerokratiecopilot.reports;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Reports and analytics (spec §26, D-008).
 *
 * <p>The specification asks for two things that most reporting code quietly skips, and both are
 * implemented as types rather than as good intentions.</p>
 *
 * <p><strong>Every metric carries its period and its filters.</strong> A number without them is not a
 * metric, it is a rumour: "12 overdue tasks" means nothing without knowing as of when and for whom. The
 * {@link Metric} record makes the period and the filters part of the value, so a caller cannot render a
 * figure without the context that makes it meaningful.</p>
 *
 * <p><strong>Approximate values are not presented as exact financial figures.</strong> A metric declares
 * whether it is exact, and when it is not, it says why in a sentence a user can read. Processing times
 * are the clearest case: they are measured from the timestamps the platform recorded, not from when the
 * work actually happened, so they are an approximation and are marked as one. Money that has been
 * recorded is exact arithmetic and is not marked.</p>
 *
 * <p>Every query is scoped to one tenant. No metric compares one tenant with another: the specification
 * forbids it, and there is no parameter that would allow it.</p>
 */
@Service
public class ReportService {

    /** Audience the report is for; the specification lists different metrics for each. */
    public enum Audience {
        INDIVIDUAL, COMPANY
    }

    /**
     * One figure, with everything needed to read it correctly.
     *
     * @param exact    whether the value is exact; when false, {@code approximationNote} says why
     * @param origin   which tables the value was computed from, so it can be checked
     */
    public record Metric(String code, String label, BigDecimal value, String unit, boolean exact,
                         String approximationNote, ReportPeriod period, Map<String, Object> filters,
                         String origin) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("code", code);
            map.put("label", label);
            map.put("value", value);
            map.put("unit", unit);
            map.put("exact", exact);
            if (!exact) {
                map.put("approximationNote", approximationNote);
            }
            map.put("period", period.asMap());
            map.put("filters", filters);
            map.put("origin", origin);
            return map;
        }
    }

    /** The period a report covers, inclusive. */
    public record ReportPeriod(LocalDate from, LocalDate to) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("from", from == null ? null : from.toString());
            map.put("to", to == null ? null : to.toString());
            return map;
        }

        public String describe() {
            return "Zeitraum: " + (from == null ? "offen" : from.toString()) + " bis "
                    + (to == null ? "offen" : to.toString());
        }
    }

    /** A whole report: the metrics plus what they have in common. */
    public record Report(Audience audience, ReportPeriod period, Map<String, Object> filters,
                         List<Metric> metrics, Instant generatedAt, List<String> notes) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("audience", audience.name());
            map.put("period", period.asMap());
            map.put("filters", filters);
            map.put("generatedAt", generatedAt.toString());
            map.put("notes", notes);
            map.put("metrics", metrics.stream().map(Metric::asMap).toList());
            return map;
        }

        /** The codes this report contains, so a caller can see what it will get. */
        public List<String> metricCodes() {
            return metrics.stream().map(Metric::code).toList();
        }

        public Metric metric(String code) {
            return metrics.stream().filter(metric -> metric.code().equals(code)).findFirst()
                    .orElseThrow(() -> ApiException.notFound("metric " + code));
        }
    }

    private final JdbcTemplate jdbc;

    public ReportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Builds a report for one tenant.
     *
     * @param workspaceId optional filter; {@code null} means the whole tenant and is recorded as such
     */
    @Transactional(readOnly = true)
    public Report build(UUID tenantId, Audience audience, ReportPeriod period, UUID workspaceId) {
        if (tenantId == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Ein Bericht gehört immer zu einem Mandanten");
        }
        if (period.from() == null || period.to() == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Ein Bericht braucht einen Zeitraum; eine Zahl ohne Zeitraum ist keine Aussage");
        }
        if (period.to().isBefore(period.from())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Das Ende liegt vor dem Anfang");
        }

        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("workspaceId", workspaceId == null ? null : workspaceId.toString());
        filters.put("workspace", workspaceId == null ? "alle" : "eingeschränkt");

        List<Metric> metrics = audience == Audience.INDIVIDUAL
                ? individualMetrics(tenantId, period, workspaceId)
                : companyMetrics(tenantId, period, workspaceId);

        List<String> notes = new ArrayList<>();
        notes.add("Alle Werte gelten nur für diesen Mandanten. Ein Vergleich mit anderen Mandanten "
                + "findet nicht statt.");
        if (metrics.stream().anyMatch(metric -> !metric.exact())) {
            notes.add("Als ungefähr gekennzeichnete Werte beruhen auf Zeitstempeln der Plattform, nicht "
                    + "auf dem tatsächlichen Zeitpunkt der Vorgänge.");
        }

        return new Report(audience, period, filters, List.copyOf(metrics), Instant.now(),
                List.copyOf(notes));
    }

    // ------------------------------------------------------------------ private individuals

    private List<Metric> individualMetrics(UUID tenantId, ReportPeriod period, UUID workspaceId) {
        List<Metric> metrics = new ArrayList<>();

        metrics.add(new Metric("UPCOMING_DEADLINES", "Anstehende Fristen",
                count("""
                        select count(*) from tasks
                        where tenant_id = ? and deleted_at is null and status not in ('DONE', 'CANCELLED')
                          and due_at::date between ? and ?
                        """, tenantId, period.from(), period.to()),
                "Aufgaben", true, null, period, filtersOf(null), "tasks.due_at, tasks.status"));

        metrics.add(new Metric("UNFINISHED_CASES", "Nicht abgeschlossene Vorgänge",
                count("""
                        select count(*) from cases
                        where tenant_id = ? and deleted_at is null and status not in ('COMPLETED', 'CLOSED',
                              'CANCELLED', 'ARCHIVED')
                        """, tenantId),
                "Vorgänge", true, null, period, filtersOf(null), "cases.status"));

        metrics.add(new Metric("EXPENSES", "Ausgaben im Zeitraum",
                amount("""
                        select coalesce(sum(gross_amount), 0) from invoices
                        where tenant_id = ? and deleted_at is null and direction = 'INCOMING'
                          and issue_date between ? and ? and status not in ('CANCELLED', 'REJECTED', 'DRAFT')
                        """, tenantId, period.from(), period.to()),
                "EUR", true, null, period, filtersOf(null),
                "invoices.gross_amount, invoices.direction, invoices.issue_date"));

        metrics.add(new Metric("ACTIVE_CONTRACTS", "Laufende Verträge",
                count("""
                        select count(*) from contracts
                        where tenant_id = ? and deleted_at is null
                          and (end_date is null or end_date >= ?)
                        """, tenantId, period.from()),
                "Verträge", true, null, period, filtersOf(null), "contracts.end_date, contracts.status"));

        metrics.add(new Metric("AWAITING_REPLY", "Warten auf Antwort",
                count("""
                        select count(*) from tasks
                        where tenant_id = ? and deleted_at is null and status = 'WAITING'
                        """, tenantId),
                "Aufgaben", true, null, period, filtersOf(null), "tasks.status"));

        metrics.add(new Metric("DOCUMENTS_TO_OBTAIN", "Noch zu beschaffende Dokumente",
                count("""
                        select count(*) from tasks
                        where tenant_id = ? and deleted_at is null and status in ('NEW', 'BLOCKED')
                          and due_at is not null and due_at::date <= ?
                        """, tenantId, period.to()),
                "Aufgaben", true, null, period, filtersOf(null), "tasks.status, tasks.due_at"));

        return metrics;
    }

    // ------------------------------------------------------------------ companies

    private List<Metric> companyMetrics(UUID tenantId, ReportPeriod period, UUID workspaceId) {
        List<Metric> metrics = new ArrayList<>();

        metrics.add(new Metric("INCOMING_BY_TYPE", "Eingegangene Dokumente nach Typ",
                count("""
                        select count(*) from documents
                        where tenant_id = ? and deleted_at is null and created_at::date between ? and ?
                        """, tenantId, period.from(), period.to()),
                "Dokumente", true, null, period, filtersOf(workspaceId),
                "documents.created_at, documents.document_type_id"));

        metrics.add(new Metric("AWAITING_MANUAL_REVIEW", "Dokumente in manueller Prüfung",
                count("""
                        select count(*) from documents
                        where tenant_id = ? and deleted_at is null
                          and (processing_status = 'REVIEW_REQUIRED' or review_status = 'PENDING'
                               or review_status = 'IN_REVIEW')
                        """, tenantId),
                "Dokumente", true, null, period, filtersOf(workspaceId),
                "documents.processing_status, documents.review_status"));

        metrics.add(new Metric("OVERDUE_TASKS", "Überfällige Aufgaben",
                count("""
                        select count(*) from tasks
                        where tenant_id = ? and deleted_at is null and status not in ('DONE', 'CANCELLED')
                          and due_at is not null and due_at::date < ?
                        """, tenantId, period.to()),
                "Aufgaben", true, null, period, filtersOf(null), "tasks.due_at, tasks.status"));

        metrics.add(new Metric("INVOICES_BY_STATUS", "Rechnungen im Zeitraum",
                count("""
                        select count(*) from invoices
                        where tenant_id = ? and deleted_at is null and issue_date between ? and ?
                        """, tenantId, period.from(), period.to()),
                "Rechnungen", true, null, period, filtersOf(null),
                "invoices.issue_date, invoices.status"));

        metrics.add(new Metric("OUTSTANDING_RECEIVABLES", "Offene Forderungen",
                amount("""
                        select coalesce(sum(gross_amount - coalesce(paid_amount, 0)), 0) from invoices
                        where tenant_id = ? and deleted_at is null and direction = 'OUTGOING'
                          and gross_amount > coalesce(paid_amount, 0)
                          and status not in ('CANCELLED', 'REJECTED', 'DRAFT')
                        """, tenantId),
                "EUR", true, null, period, filtersOf(null),
                "invoices.gross_amount, invoices.paid_amount"));

        metrics.add(new Metric("EXPENSES", "Ausgaben im Zeitraum",
                amount("""
                        select coalesce(sum(gross_amount), 0) from invoices
                        where tenant_id = ? and deleted_at is null and direction = 'INCOMING'
                          and issue_date between ? and ? and status not in ('CANCELLED', 'REJECTED', 'DRAFT')
                        """, tenantId, period.from(), period.to()),
                "EUR", true, null, period, filtersOf(null),
                "invoices.gross_amount, invoices.direction"));

        metrics.add(new Metric("PROCESSING_TIME_AVERAGE", "Durchschnittliche Bearbeitungszeit",
                amount("""
                        select coalesce(avg(extract(epoch from (completed_at - created_at)) / 3600.0), 0)
                        from tasks
                        where tenant_id = ? and deleted_at is null and status = 'DONE'
                          and completed_at is not null
                          and completed_at::date between ? and ?
                        """, tenantId, period.from(), period.to()),
                "Stunden", false,
                "Gemessen an den Zeitstempeln der Plattform: der Zeitpunkt, zu dem eine Aufgabe erfasst "
                        + "und abgeschlossen wurde. Wartezeiten außerhalb der Plattform — etwa eine "
                        + "Antwort per Post — sind darin nicht enthalten.",
                period, filtersOf(null), "tasks.created_at, tasks.completed_at, tasks.status"));

        metrics.add(new Metric("EMPLOYEE_WORKLOAD", "Offene Aufgaben je Mitarbeiter",
                amount("""
                        select coalesce(avg(open_tasks), 0) from (
                          select count(*) as open_tasks
                          from tasks t
                          join task_assignees a on a.task_id = t.id
                          where t.tenant_id = ? and t.deleted_at is null
                            and t.status not in ('DONE', 'CANCELLED')
                          group by a.user_id
                        ) per_person
                        """, tenantId),
                "Aufgaben", false,
                "Ein Durchschnitt offener Aufgaben je zugewiesener Person. Das ist ein Zählwert und keine "
                        + "Aussage über Auslastung: Aufgaben sind unterschiedlich groß, und die Zahl "
                        + "berücksichtigt keine Arbeitszeit.",
                period, filtersOf(null), "tasks.status, task_assignees.user_id"));

        metrics.add(new Metric("AUTOMATION_ERRORS", "Fehler der Automatisierung",
                count("""
                        select count(*) from documents
                        where tenant_id = ? and deleted_at is null and processing_status = 'FAILED'
                          and updated_at::date between ? and ?
                        """, tenantId, period.from(), period.to()),
                "Dokumente", true, null, period, filtersOf(workspaceId),
                "documents.processing_status, documents.updated_at"));

        metrics.add(new Metric("STRAIGHT_THROUGH_SHARE", "Anteil ohne manuelle Prüfung",
                amount("""
                        select case when count(*) = 0 then 0
                               else round(100.0 * count(*) filter (where processing_status in ('READY',
                                    'COMPLETED')) / count(*), 1) end
                        from documents
                        where tenant_id = ? and deleted_at is null
                          and created_at::date between ? and ?
                        """, tenantId, period.from(), period.to()),
                "Prozent", false,
                "Anteil der Dokumente, die ohne manuelle Prüfung durchliefen. Der Wert sagt nichts über "
                        + "die Richtigkeit des Ergebnisses: ein Dokument kann fehlerfrei durchgelaufen "
                        + "und trotzdem falsch eingeordnet sein. Verlässlich ist er erst zusammen mit "
                        + "den Fehlerzahlen.",
                period, filtersOf(workspaceId),
                "documents.processing_status, documents.review_status"));

        return metrics;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The filters that applied, recorded with the metric.
     *
     * <p>Deliberately part of every value: the specification asks for the filters to be shown, and a
     * figure whose filters are unknown cannot be checked.</p>
     */
    private static Map<String, Object> filtersOf(UUID workspaceId) {
        Map<String, Object> filters = new LinkedHashMap<>();
        // Null is a meaningful value here: it says the report was not restricted to one workspace.
        // Map.copyOf refuses null values, which is why this is an unmodifiable map of a mutable one.
        filters.put("workspaceId", workspaceId == null ? null : workspaceId.toString());
        return java.util.Collections.unmodifiableMap(filters);
    }

    private BigDecimal count(String sql, Object... arguments) {
        Long value = jdbc.queryForObject(sql, Long.class, arguments);
        return BigDecimal.valueOf(value == null ? 0 : value);
    }

    private BigDecimal amount(String sql, Object... arguments) {
        BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class, arguments);
        return value == null ? BigDecimal.ZERO : value;
    }

    /** The metric codes a report of an audience contains, for a caller that wants to know in advance. */
    public List<String> metricCodes(Audience audience) {
        UUID tenantId = UUID.randomUUID();
        // A dry run with no data: the codes do not depend on the numbers, and this keeps the list in one
        // place instead of duplicating it in the API layer.
        ReportPeriod period = new ReportPeriod(LocalDate.of(2000, 1, 1), LocalDate.of(2000, 1, 2));
        List<Metric> metrics = audience == Audience.INDIVIDUAL
                ? individualMetrics(tenantId, period, null)
                : companyMetrics(tenantId, period, null);
        return metrics.stream().map(Metric::code).toList();
    }
}
