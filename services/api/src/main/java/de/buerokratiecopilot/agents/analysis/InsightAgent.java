package de.buerokratiecopilot.agents.analysis;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
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
 * {@code insight} — Auswertungen.
 *
 * <p>Turns the collected metrics into plain conclusions a person can act on: a fixed set of
 * rules inspects the platform's own figures (overdue tasks, failed documents, open receivables
 * and payables, unmatched bank movements, pending approvals, integration health, deadline
 * load) and produces findings with a severity and a concrete recommendation. Rule-based on
 * purpose — deterministic, explainable, no external AI.</p>
 *
 * <p>Honest in both directions: a tenant without any data is skipped, and a tenant whose
 * figures are all within the thresholds gets an explicit "nothing to report" success rather
 * than invented findings. Read-only and idempotent: nothing is persisted, refs are the rule
 * ids.</p>
 */
@Component
public class InsightAgent implements Agent {

    // Thresholds mirrored in the manifest config.
    private static final BigDecimal RECEIVABLES_WARN = new BigDecimal("1000");
    private static final BigDecimal RECEIVABLES_CRIT = new BigDecimal("10000");
    private static final BigDecimal PAYABLES_WARN = new BigDecimal("1000");
    private static final BigDecimal PAYABLES_CRIT = new BigDecimal("10000");
    private static final long OVERDUE_TASKS_CRIT = 10;
    private static final long PENDING_DOCUMENTS_INFO = 5;
    private static final long DEADLINES_NEXT7_INFO = 10;
    private static final long UNMATCHED_CRIT = 20;
    private static final Duration STALE_SYNC_AFTER = Duration.ofDays(1);
    private static final Duration DEADLINE_HORIZON = Duration.ofDays(7);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public InsightAgent(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return "insight";
    }

    @Override
    @Transactional(readOnly = true)
    public AgentResult run(AgentContext context) {
        BaseMetrics metrics = gather(context.tenantId(), context.now());
        return buildResult(metrics, evaluate(metrics));
    }

    /** The figures the rules judge; {@code hasData()} decides between "no data" and "all fine". */
    record BaseMetrics(long documentsTotal, long tasksTotal, long invoicesTotal,
                       long accountsTotal, long enabledIntegrations,
                       long overdueTasks, long failedDocuments, long pendingDocuments,
                       BigDecimal receivables, BigDecimal payables,
                       long unmatchedTransactions, long deadlinesNext7,
                       long paymentsAwaitingApproval, long staleIntegrations,
                       long errorIntegrations) {

        boolean hasData() {
            return documentsTotal > 0 || tasksTotal > 0 || invoicesTotal > 0
                    || accountsTotal > 0 || enabledIntegrations > 0;
        }
    }

    /** One rule firing: what is wrong, how badly, and what to do about it. */
    record Finding(String id, String severity, String text, String recommendation) {
    }

    BaseMetrics gather(UUID tenantId, Instant now) {
        return new BaseMetrics(
                count("select count(*) from documents where tenant_id = ? and deleted_at is null",
                        tenantId),
                count("select count(*) from tasks where tenant_id = ? and deleted_at is null",
                        tenantId),
                count("select count(*) from invoices where tenant_id = ? and deleted_at is null",
                        tenantId),
                count("select count(*) from financial_accounts where tenant_id = ? and deleted_at is null and is_active",
                        tenantId),
                count("select count(*) from integrations where tenant_id = ? and deleted_at is null and is_enabled",
                        tenantId),
                count("""
                        select count(*) from tasks
                        where tenant_id = ? and deleted_at is null and status not in ('DONE', 'CANCELLED')
                          and due_at < ?
                        """, tenantId, java.sql.Timestamp.from(now)),
                count("""
                        select count(*) from documents
                        where tenant_id = ? and deleted_at is null and processing_status = 'FAILED'
                        """, tenantId),
                count("""
                        select count(*) from documents
                        where tenant_id = ? and deleted_at is null
                          and (processing_status = 'REVIEW_REQUIRED' or review_status = 'PENDING'
                               or review_status = 'IN_REVIEW')
                        """, tenantId),
                amount("""
                        select coalesce(sum(gross_amount - coalesce(paid_amount, 0)), 0) from invoices
                        where tenant_id = ? and deleted_at is null and direction = 'OUTGOING'
                          and gross_amount > coalesce(paid_amount, 0)
                          and status not in ('DRAFT', 'CANCELLED', 'CREDITED', 'REJECTED')
                        """, tenantId),
                amount("""
                        select coalesce(sum(gross_amount - coalesce(paid_amount, 0)), 0) from invoices
                        where tenant_id = ? and deleted_at is null and direction = 'INCOMING'
                          and gross_amount > coalesce(paid_amount, 0)
                          and status not in ('DRAFT', 'CANCELLED', 'CREDITED', 'REJECTED')
                        """, tenantId),
                count("""
                        select count(*) from bank_transactions
                        where tenant_id = ? and status in ('UNMATCHED', 'SUGGESTED')
                        """, tenantId),
                count("""
                        select count(*) from tasks
                        where tenant_id = ? and deleted_at is null and status not in ('DONE', 'CANCELLED')
                          and due_at >= ? and due_at < ?
                        """, tenantId, java.sql.Timestamp.from(now),
                        java.sql.Timestamp.from(now.plus(DEADLINE_HORIZON))),
                count("""
                        select count(*) from payments
                        where tenant_id = ? and deleted_at is null and status = 'AWAITING_APPROVAL'
                        """, tenantId),
                count("""
                        select count(*) from integrations
                        where tenant_id = ? and deleted_at is null and is_enabled and status = 'CONNECTED'
                          and (last_sync_at is null or last_sync_at < ?)
                        """, tenantId, java.sql.Timestamp.from(now.minus(STALE_SYNC_AFTER))),
                count("""
                        select count(*) from integrations
                        where tenant_id = ? and deleted_at is null and is_enabled
                          and status in ('ERROR', 'DEGRADED')
                        """, tenantId));
    }

    /**
     * The rules themselves — pure and unit-testable. Each rule fires at most once per run and
     * carries a severity ({@code info|warning|critical}) and a recommendation in plain German.
     */
    List<Finding> evaluate(BaseMetrics metrics) {
        List<Finding> findings = new ArrayList<>();
        if (metrics.overdueTasks() > 0) {
            findings.add(new Finding("OVERDUE_TASKS", severity(metrics.overdueTasks(), OVERDUE_TASKS_CRIT),
                    metrics.overdueTasks() + " Aufgaben sind überfällig.",
                    "Überfällige Aufgaben priorisieren und Fälligkeiten neu planen."));
        }
        if (metrics.failedDocuments() > 0) {
            findings.add(new Finding("FAILED_DOCUMENTS", "critical",
                    metrics.failedDocuments() + " Dokumente sind in der Verarbeitung fehlgeschlagen.",
                    "Fehlgeschlagene Dokumente prüfen und die Verarbeitung erneut anstoßen."));
        }
        if (metrics.receivables().compareTo(RECEIVABLES_WARN) > 0) {
            findings.add(new Finding("OPEN_RECEIVABLES",
                    severity(metrics.receivables(), RECEIVABLES_CRIT),
                    "Offene Forderungen von " + money(metrics.receivables()) + " EUR.",
                    "Mahnwesen prüfen und Zahlungseingänge nachhalten."));
        }
        if (metrics.payables().compareTo(PAYABLES_WARN) > 0) {
            findings.add(new Finding("OPEN_PAYABLES",
                    severity(metrics.payables(), PAYABLES_CRIT),
                    "Offene Verbindlichkeiten von " + money(metrics.payables()) + " EUR.",
                    "Zahlungen planen und Fälligkeiten im Blick behalten."));
        }
        if (metrics.unmatchedTransactions() > 0) {
            findings.add(new Finding("UNMATCHED_TRANSACTIONS",
                    severity(metrics.unmatchedTransactions(), UNMATCHED_CRIT),
                    metrics.unmatchedTransactions() + " Kontobewegungen ohne Zuordnung.",
                    "Zahlungen den Rechnungen zuordnen."));
        }
        if (metrics.pendingDocuments() > PENDING_DOCUMENTS_INFO) {
            findings.add(new Finding("DOCUMENTS_IN_REVIEW", "info",
                    metrics.pendingDocuments() + " Dokumente warten auf manuelle Prüfung.",
                    "Prüfschlange abarbeiten, damit Vorgänge nicht warten."));
        }
        if (metrics.deadlinesNext7() > DEADLINES_NEXT7_INFO) {
            findings.add(new Finding("DEADLINE_LOAD", "info",
                    metrics.deadlinesNext7() + " Fristen fallen in die nächsten 7 Tage.",
                    "Kapazität planen und Erinnerungen aktivieren."));
        }
        if (metrics.paymentsAwaitingApproval() > 0) {
            findings.add(new Finding("PAYMENTS_AWAITING_APPROVAL", "warning",
                    metrics.paymentsAwaitingApproval() + " Zahlungen warten auf Freigabe.",
                    "Freigaben zeitnah entscheiden."));
        }
        if (metrics.errorIntegrations() > 0) {
            findings.add(new Finding("INTEGRATION_ERRORS", "critical",
                    metrics.errorIntegrations() + " verbundene Systeme melden Fehler.",
                    "Verbindungen prüfen und erneut verbinden."));
        }
        if (metrics.staleIntegrations() > 0) {
            findings.add(new Finding("INTEGRATIONS_STALE", "warning",
                    metrics.staleIntegrations() + " Systeme wurden seit über einem Tag nicht synchronisiert.",
                    "Synchronisierung anstoßen oder Verbindung prüfen."));
        }
        return List.copyOf(findings);
    }

    /**
     * Assembles the run result — pure, unit-testable: no data → honest skip, no findings →
     * explicit "all fine", findings → one produced entry per finding plus a JSON detail in meta.
     */
    AgentResult buildResult(BaseMetrics metrics, List<Finding> findings) {
        if (!metrics.hasData()) {
            return AgentResult.skipped("Keine Daten für eine Auswertung: keine Dokumente, Aufgaben, "
                    + "Rechnungen, Konten oder verbundenen Systeme.");
        }
        if (findings.isEmpty()) {
            return AgentResult.done("Keine Auffälligkeiten: alle geprüften Kennzahlen liegen im "
                    + "Rahmen.", List.of(), Map.of("findings", "0"));
        }
        String summary = findings.size() + " Auffälligkeit(en): " + findings.stream().limit(3)
                .map(finding -> finding.id() + " (" + finding.severity() + ")")
                .reduce((first, second) -> first + ", " + second).orElse("");
        List<AgentResult.Produced> produced = findings.stream()
                .map(finding -> new AgentResult.Produced("insight", finding.id(),
                        finding.severity() + ": " + finding.text()))
                .toList();
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("findings", Integer.toString(findings.size()));
        meta.put("findingsDetail", toJson(findings.stream().map(finding -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", finding.id());
            row.put("severity", finding.severity());
            row.put("text", finding.text());
            row.put("recommendation", finding.recommendation());
            return row;
        }).toList()));
        return AgentResult.done(summary, produced, meta);
    }

    private static String severity(long value, long criticalAt) {
        return value >= criticalAt ? "critical" : "warning";
    }

    private static String severity(BigDecimal value, BigDecimal criticalAt) {
        return value.compareTo(criticalAt) >= 0 ? "critical" : "warning";
    }

    private static String money(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private long count(String sql, Object... arguments) {
        Long value = jdbc.queryForObject(sql, Long.class, arguments);
        return value == null ? 0 : value;
    }

    private BigDecimal amount(String sql, Object... arguments) {
        BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class, arguments);
        return value == null ? BigDecimal.ZERO : value;
    }
}
