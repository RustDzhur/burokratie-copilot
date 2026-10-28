package de.buerokratiecopilot.agents.analysis;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;

/**
 * {@code metric-collect} — Kennzahlen-Sammlung.
 *
 * <p>Collects figures from the connected systems — enabled integrations plus the platform's own
 * modules (documents, tasks, invoices, banking) — and merges them into one unified metric set:
 * every point carries its {@code source}, {@code code}, {@code value} and {@code unit}. The set
 * is returned in {@code meta.metricSet} as JSON, so downstream agents (anomaly, insight) and the
 * UI can consume it without knowing where a number came from.</p>
 *
 * <p>Honest by design: a source that contributes only zeros is dropped from the set, and a tenant
 * without any data yields {@code ok=false} instead of a set of zeros. Idempotent: read-only,
 * nothing persisted, deterministic refs per source.</p>
 */
@Component
public class MetricCollectAgent implements Agent {

    private static final Duration STALE_SYNC_AFTER = Duration.ofDays(1);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public MetricCollectAgent(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return "metric-collect";
    }

    @Override
    @Transactional(readOnly = true)
    public AgentResult run(AgentContext context) {
        LocalDate day = AnalysisDates.day(context.now());
        return assemble(gather(context.tenantId(), context.now()), day);
    }

    /** One figure of the unified set; {@code value} stays numeric so consumers can compute. */
    record MetricPoint(String code, BigDecimal value, String unit) {
    }

    /** The metrics of one connected system (an integration family or a platform module). */
    record SourceMetrics(String source, String label, List<MetricPoint> points) {
    }

    /** The raw material: every source with all its points, zeros included. */
    Map<String, SourceMetrics> gather(UUID tenantId, Instant now) {
        Map<String, SourceMetrics> sources = new LinkedHashMap<>();
        sources.put("integrations", integrations(tenantId, now));
        sources.put("documents", documents(tenantId));
        sources.put("tasks", tasks(tenantId, now));
        sources.put("invoices", invoices(tenantId));
        sources.put("banking", banking(tenantId, now));
        return sources;
    }

    private SourceMetrics integrations(UUID tenantId, Instant now) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select status, last_sync_at from integrations
                where tenant_id = ? and deleted_at is null and is_enabled
                """, tenantId);
        long total = rows.size();
        long connected = rows.stream().filter(r -> "CONNECTED".equals(r.get("status"))).count();
        long errors = rows.stream()
                .filter(r -> "ERROR".equals(r.get("status")) || "DEGRADED".equals(r.get("status")))
                .count();
        long stale = rows.stream().filter(r -> staleSync(r, now)).count();
        return new SourceMetrics("integrations", "Verbundene Systeme", List.of(
                new MetricPoint("total", value(total), "Integrationen"),
                new MetricPoint("connected", value(connected), "Integrationen"),
                new MetricPoint("errors", value(errors), "Integrationen"),
                new MetricPoint("stale", value(stale), "Integrationen")));
    }

    private boolean staleSync(Map<String, Object> row, Instant now) {
        if (!"CONNECTED".equals(row.get("status"))) {
            return false;
        }
        Timestamp lastSync = (Timestamp) row.get("last_sync_at");
        return lastSync == null || lastSync.toInstant().isBefore(now.minus(STALE_SYNC_AFTER));
    }

    private SourceMetrics documents(UUID tenantId) {
        return new SourceMetrics("documents", "Dokumente", List.of(
                new MetricPoint("total", count("""
                        select count(*) from documents where tenant_id = ? and deleted_at is null
                        """, tenantId), "Dokumente"),
                new MetricPoint("reviewRequired", count("""
                        select count(*) from documents where tenant_id = ? and deleted_at is null
                          and processing_status = 'REVIEW_REQUIRED'
                        """, tenantId), "Dokumente"),
                new MetricPoint("failed", count("""
                        select count(*) from documents where tenant_id = ? and deleted_at is null
                          and processing_status = 'FAILED'
                        """, tenantId), "Dokumente"),
                new MetricPoint("completed", count("""
                        select count(*) from documents where tenant_id = ? and deleted_at is null
                          and processing_status in ('COMPLETED', 'READY')
                        """, tenantId), "Dokumente")));
    }

    private SourceMetrics tasks(UUID tenantId, Instant now) {
        return new SourceMetrics("tasks", "Aufgaben", List.of(
                new MetricPoint("open", count("""
                        select count(*) from tasks where tenant_id = ? and deleted_at is null
                          and status not in ('DONE', 'CANCELLED')
                        """, tenantId), "Aufgaben"),
                new MetricPoint("overdue", count("""
                        select count(*) from tasks where tenant_id = ? and deleted_at is null
                          and status not in ('DONE', 'CANCELLED') and due_at < ?
                        """, tenantId, java.sql.Timestamp.from(now)), "Aufgaben"),
                new MetricPoint("waiting", count("""
                        select count(*) from tasks where tenant_id = ? and deleted_at is null
                          and status = 'WAITING'
                        """, tenantId), "Aufgaben")));
    }

    private SourceMetrics invoices(UUID tenantId) {
        return new SourceMetrics("invoices", "Rechnungen", List.of(
                new MetricPoint("receivables", amount("""
                        select coalesce(sum(gross_amount - coalesce(paid_amount, 0)), 0) from invoices
                        where tenant_id = ? and deleted_at is null and direction = 'OUTGOING'
                          and gross_amount > coalesce(paid_amount, 0)
                          and status not in ('DRAFT', 'CANCELLED', 'CREDITED', 'REJECTED')
                        """, tenantId), "EUR"),
                new MetricPoint("payables", amount("""
                        select coalesce(sum(gross_amount - coalesce(paid_amount, 0)), 0) from invoices
                        where tenant_id = ? and deleted_at is null and direction = 'INCOMING'
                          and gross_amount > coalesce(paid_amount, 0)
                          and status not in ('DRAFT', 'CANCELLED', 'CREDITED', 'REJECTED')
                        """, tenantId), "EUR"),
                new MetricPoint("open", count("""
                        select count(*) from invoices where tenant_id = ? and deleted_at is null
                          and payment_status <> 'PAID'
                          and status not in ('DRAFT', 'CANCELLED', 'CREDITED', 'REJECTED')
                        """, tenantId), "Rechnungen")));
    }

    private SourceMetrics banking(UUID tenantId, Instant now) {
        return new SourceMetrics("banking", "Banking", List.of(
                new MetricPoint("accounts", count("""
                        select count(*) from financial_accounts
                        where tenant_id = ? and deleted_at is null and is_active
                        """, tenantId), "Konten"),
                new MetricPoint("unmatchedTransactions", count("""
                        select count(*) from bank_transactions
                        where tenant_id = ? and status in ('UNMATCHED', 'SUGGESTED')
                        """, tenantId), "Buchungen"),
                new MetricPoint("awaitingApproval", count("""
                        select count(*) from payments
                        where tenant_id = ? and deleted_at is null and status = 'AWAITING_APPROVAL'
                        """, tenantId), "Zahlungen"),
                new MetricPoint("statements30d", count("""
                        select count(*) from bank_statements
                        where tenant_id = ? and created_at >= ?
                        """, tenantId, java.sql.Timestamp.from(now.minus(Duration.ofDays(30)))), "Auszüge")));
    }

    /**
     * Merges the sources into the unified set and builds the result — pure, unit-testable:
     * sources contributing only zeros are dropped, an all-zero tenant is reported honestly as
     * skipped, and the surviving points are serialised into {@code meta.metricSet}.
     */
    AgentResult assemble(Map<String, SourceMetrics> sources, LocalDate day) {
        Map<String, SourceMetrics> contributing = new LinkedHashMap<>();
        for (Map.Entry<String, SourceMetrics> entry : sources.entrySet()) {
            boolean hasValue = entry.getValue().points().stream()
                    .anyMatch(point -> point.value().signum() != 0);
            if (hasValue) {
                contributing.put(entry.getKey(), entry.getValue());
            }
        }
        if (contributing.isEmpty()) {
            return AgentResult.skipped("Keine Daten gesammelt: keine verbundenen Systeme und "
                    + "keine Datensätze in Dokumenten, Aufgaben, Rechnungen oder Banking.");
        }

        List<Map<String, Object>> unified = new ArrayList<>();
        for (SourceMetrics source : contributing.values()) {
            for (MetricPoint point : source.points()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("source", source.source());
                row.put("system", source.label());
                row.put("code", point.code());
                row.put("value", point.value());
                row.put("unit", point.unit());
                unified.add(row);
            }
        }
        long metricCount = contributing.values().stream()
                .mapToLong(source -> source.points().size()).sum();
        List<AgentResult.Produced> produced = contributing.values().stream()
                .map(source -> new AgentResult.Produced("metrics", source.source(),
                        source.points().size() + " Kennzahlen aus " + source.label()))
                .toList();

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("date", day.toString());
        meta.put("sources", Integer.toString(contributing.size()));
        meta.put("metrics", Long.toString(metricCount));
        try {
            meta.put("metricSet", mapper.writeValueAsString(unified));
        } catch (JsonProcessingException e) {
            meta.put("metricSet", "[]");
        }
        String summary = metricCount + " Kennzahlen aus " + contributing.size()
                + " Systemen zu einem einheitlichen Set zusammengeführt.";
        return AgentResult.done(summary, produced, meta);
    }

    private static BigDecimal value(long number) {
        return BigDecimal.valueOf(number);
    }

    private BigDecimal count(String sql, Object... arguments) {
        Long value = jdbc.queryForObject(sql, Long.class, arguments);
        return value(value == null ? 0 : value);
    }

    private BigDecimal amount(String sql, Object... arguments) {
        BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class, arguments);
        return value == null ? BigDecimal.ZERO : value;
    }
}
