package de.buerokratiecopilot.agents.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;

/**
 * End-to-end runs of the five analysis agents against a real PostgreSQL database
 * (spec §28.1 — no in-memory substitution). Seeds the module tables with real rows, runs the
 * agents as the runtime would, and checks that the arithmetic lands in the result.
 */
@SpringBootTest
@ActiveProfiles("test")
class AnalysisAgentsIT {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 20);
    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DailyReportAgent dailyReport;

    @Autowired
    private MetricCollectAgent metricCollect;

    @Autowired
    private AnomalyAgent anomaly;

    @Autowired
    private ForecastAgent forecast;

    @Autowired
    private InsightAgent insight;

    @Autowired
    private ObjectMapper mapper;

    // ------------------------------------------------------------------ seeding

    private UUID tenant() {
        return jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'Analyse Test', 'COMPANY') returning id
                """, UUID.class, "ana-" + UUID.randomUUID().toString().substring(0, 12));
    }

    private UUID user() {
        return jdbc.queryForObject("""
                insert into users (id, email, display_name)
                values (gen_random_uuid(), ?, 'Testnutzer') returning id
                """, UUID.class, "user-" + UUID.randomUUID().toString().substring(0, 8)
                        + "@example.com");
    }

    private void document(UUID tenantId, Instant createdAt, String status) {
        String hash = UUID.randomUUID().toString().replace("-", "") + "0".repeat(32);
        jdbc.update("""
                insert into documents (id, tenant_id, original_filename, internal_name, mime_type,
                    size_bytes, content_hash, processing_status, review_status, created_at)
                values (gen_random_uuid(), ?, ?, ?, 'application/pdf', 1, ?, ?, 'NOT_REQUIRED', ?)
                """, tenantId, "d.pdf", "d.pdf", hash, status, java.sql.Timestamp.from(createdAt));
    }

    private void task(UUID tenantId, Instant createdAt, Instant dueAt, String status) {
        java.sql.Timestamp completed = status.equals("DONE")
                ? java.sql.Timestamp.from(createdAt) : null;
        jdbc.update("""
                insert into tasks (id, tenant_id, title, status, priority, source, due_at,
                                   completed_at, metadata, created_at)
                values (gen_random_uuid(), ?, 'Aufgabe', ?, 'NORMAL', 'MANUAL', ?, ?, '{}'::jsonb, ?)
                """, tenantId, status, dueAt == null ? null : java.sql.Timestamp.from(dueAt),
                completed, java.sql.Timestamp.from(createdAt));
    }

    private void invoice(UUID tenantId, String direction, String gross, LocalDate issueDate,
                         LocalDate dueDate, String status) {
        jdbc.update("""
                insert into invoices (id, tenant_id, direction, invoice_type, invoice_number, currency,
                                      net_amount, gross_amount, paid_amount, status, payment_status,
                                      source, validation_state, validation_flags, bank_details_changed,
                                      metadata, issue_date, due_date)
                values (gen_random_uuid(), ?, ?, 'INVOICE', ?, 'EUR', ?::numeric, ?::numeric,
                        ?::numeric, ?, 'UNPAID', 'MANUAL', '{}'::jsonb, '[]'::jsonb, false,
                        '{}'::jsonb, ?::date, ?::date)
                """, tenantId, direction, "RE-" + UUID.randomUUID().toString().substring(0, 8),
                gross, gross, 0, status, issueDate, dueDate);
    }

    private void payment(UUID tenantId, UUID confirmedBy, String amount, LocalDate paymentDate) {
        jdbc.update("""
                insert into payments (id, tenant_id, direction, amount, status, payment_date,
                                      confirmed_by, confirmed_at, executed_at, metadata)
                values (gen_random_uuid(), ?, 'OUTGOING', ?::numeric, 'EXECUTED', ?::date,
                        ?, now(), now(), '{}'::jsonb)
                """, tenantId, amount, paymentDate, confirmedBy);
    }

    private void bankTransaction(UUID tenantId, String amount, LocalDate bookingDate) {
        jdbc.update("""
                insert into bank_transactions (id, tenant_id, booking_date, amount, status, metadata)
                values (gen_random_uuid(), ?, ?::date, ?::numeric, 'UNMATCHED', '{}'::jsonb)
                """, tenantId, bookingDate, amount);
    }

    private void account(UUID tenantId) {
        jdbc.update("""
                insert into financial_accounts (id, tenant_id, name, account_type)
                values (gen_random_uuid(), ?, 'Hauptkonto', 'BANK')
                """, tenantId);
    }

    private void integration(UUID tenantId, String status, Instant lastSyncAt) {
        jdbc.update("""
                insert into integrations (id, tenant_id, provider, category, display_name, status,
                                          is_enabled, last_sync_at, config, granted_scopes)
                values (gen_random_uuid(), ?, 'CALENDAR_GOOGLE', 'CALENDAR', 'Kalender', ?, true,
                        ?, '{}'::jsonb, '[]'::jsonb)
                """, tenantId, status, lastSyncAt == null ? null : java.sql.Timestamp.from(lastSyncAt));
    }

    private static Instant noon(LocalDate day) {
        return day.atTime(12, 0).atZone(BERLIN).toInstant();
    }

    private AgentContext ctx(UUID tenantId, Map<String, String> inputs) {
        return new AgentContext(tenantId, null, "manual", NOW, inputs);
    }

    // ------------------------------------------------------------------ tests

    @Test
    void dailyReportAggregatesTheSeededDayAndIsRepeatable() {
        UUID tenantId = tenant();
        UUID confirmedBy = user();
        document(tenantId, noon(DAY), "READY");
        document(tenantId, noon(DAY), "READY");
        task(tenantId, noon(DAY), null, "NEW");
        task(tenantId, noon(DAY.minusDays(1)), noon(DAY), "NEW"); // due today
        invoice(tenantId, "OUTGOING", "500.00", DAY.minusDays(5), DAY, "RECEIVED");
        invoice(tenantId, "INCOMING", "300.00", DAY.minusDays(5), DAY.plusDays(14), "RECEIVED");
        payment(tenantId, confirmedBy, "120.00", DAY);
        bankTransaction(tenantId, "50.00", DAY);
        bankTransaction(tenantId, "-30.00", DAY);

        AgentResult result = dailyReport.run(ctx(tenantId, Map.of("date", "2026-10-20")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("documents", "2")
                .containsEntry("tasks", "1")
                .containsEntry("deadlines", "1")
                .containsEntry("invoicesDue", "1")
                .containsEntry("payments", "1")
                .containsEntry("paymentsAmount", "120")
                .containsEntry("bankTransactions", "2")
                .containsEntry("bankIncoming", "50")
                .containsEntry("bankOutgoing", "30");
        assertThat(result.meta()).containsKey("expenses30d"); // 300 EUR via reports module
        assertThat(result.meta()).containsEntry("receivables", "500");
        assertThat(result.produced()).extracting(produced -> produced.ref())
                .containsExactly("daily:2026-10-20");

        // Idempotent: the same run again yields the same result and duplicates nothing.
        AgentResult again = dailyReport.run(ctx(tenantId, Map.of("date", "2026-10-20")));
        assertThat(again.meta()).isEqualTo(result.meta());
        assertThat(jdbc.queryForObject(
                "select count(*) from documents where tenant_id = ?", Long.class, tenantId))
                .isEqualTo(2);
    }

    @Test
    void metricCollectUnifiesConnectedSystems() throws Exception {
        UUID tenantId = tenant();
        integration(tenantId, "CONNECTED", Instant.parse("2026-10-26T10:00:00Z"));
        document(tenantId, noon(DAY), "READY");
        invoice(tenantId, "OUTGOING", "500.00", DAY.minusDays(2), DAY.plusDays(10), "RECEIVED");
        account(tenantId);
        bankTransaction(tenantId, "75.00", DAY);

        AgentResult result = metricCollect.run(ctx(tenantId, Map.of()));

        assertThat(result.ok()).isTrue();
        JsonNode set = mapper.readTree(result.meta().get("metricSet"));
        assertThat(set).isNotEmpty();
        assertThat(result.meta().get("sources")).isEqualTo("4"); // integrations, documents, invoices, banking
        JsonNode receivables = find(set, "invoices", "receivables");
        assertThat(receivables).isNotNull();
        assertThat(receivables.get("value").decimalValue()).isEqualByComparingTo("500");
        JsonNode connected = find(set, "integrations", "connected");
        assertThat(connected.get("value").asInt()).isEqualTo(1);
        assertThat(result.produced()).extracting(produced -> produced.kind())
                .containsOnly("metrics");
    }

    @Test
    void anomalyFlagsTheSeededSpike() throws Exception {
        UUID tenantId = tenant();
        // Ten quiet days with 3 documents and one day with 25.
        for (int i = 1; i <= 10; i++) {
            LocalDate day = LocalDate.of(2026, 10, 16).plusDays(i - 1);
            int count = i == 8 ? 25 : 3;
            for (int n = 0; n < count; n++) {
                document(tenantId, noon(day), "READY");
            }
        }

        AgentResult result = anomaly.run(ctx(tenantId, Map.of("windowDays", "12")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta().get("anomalies")).isEqualTo("1");
        JsonNode detail = mapper.readTree(result.meta().get("anomaliesDetail"));
        assertThat(detail.get(0).get("series").asText()).isEqualTo("documents");
        assertThat(detail.get(0).get("day").asText()).isEqualTo("2026-10-23");
        assertThat(detail.get(0).get("value").asDouble()).isEqualTo(25.0);
        assertThat(detail.get(0).get("z").asDouble()).isGreaterThan(2.0);
        assertThat(result.produced()).hasSize(1);
    }

    @Test
    void forecastProjectsTheSeededTrend() throws Exception {
        UUID tenantId = tenant();
        // Tasks created 1, 2, ..., 12 per day — a clean upward line.
        for (int i = 1; i <= 12; i++) {
            LocalDate day = LocalDate.of(2026, 10, 15).plusDays(i - 1);
            for (int n = 0; n < i; n++) {
                task(tenantId, noon(day), null, "NEW");
            }
        }

        AgentResult result = forecast.run(ctx(tenantId, Map.of("windowDays", "14")));

        assertThat(result.ok()).isTrue();
        JsonNode root = mapper.readTree(result.meta().get("forecast"));
        assertThat(root).hasSize(1);
        JsonNode tasks = root.get(0);
        assertThat(tasks.get("series").asText()).isEqualTo("tasks");
        assertThat(tasks.get("points").asInt()).isEqualTo(12);
        assertThat(tasks.get("slope").asDouble()).isCloseTo(1.0,
                org.assertj.core.data.Offset.offset(0.05));
        assertThat(tasks.get("forecast")).hasSize(7);
        assertThat(result.summary()).contains("Vorbehalte");
    }

    @Test
    void insightFindsSeededProblems() throws Exception {
        UUID tenantId = tenant();
        task(tenantId, noon(DAY.minusDays(5)), Instant.parse("2026-10-25T10:00:00Z"), "NEW");
        task(tenantId, noon(DAY.minusDays(4)), Instant.parse("2026-10-24T10:00:00Z"), "NEW");
        task(tenantId, noon(DAY.minusDays(3)), Instant.parse("2026-10-23T10:00:00Z"), "NEW");
        document(tenantId, noon(DAY.minusDays(1)), "FAILED");

        AgentResult result = insight.run(ctx(tenantId, Map.of()));

        assertThat(result.ok()).isTrue();
        JsonNode detail = mapper.readTree(result.meta().get("findingsDetail"));
        assertThat(detail).hasSize(2);
        assertThat(detail.findValuesAsText("id"))
                .containsExactlyInAnyOrder("OVERDUE_TASKS", "FAILED_DOCUMENTS");
        assertThat(result.produced()).extracting(produced -> produced.kind())
                .containsOnly("insight");
    }

    @Test
    void emptyTenantIsSkippedHonestlyByEveryAgent() {
        UUID tenantId = tenant();

        assertThat(dailyReport.run(ctx(tenantId, Map.of())).ok()).isFalse();
        assertThat(metricCollect.run(ctx(tenantId, Map.of())).ok()).isFalse();
        assertThat(anomaly.run(ctx(tenantId, Map.of())).ok()).isFalse();
        assertThat(forecast.run(ctx(tenantId, Map.of())).ok()).isFalse();
        assertThat(insight.run(ctx(tenantId, Map.of())).ok()).isFalse();
    }

    private static JsonNode find(JsonNode set, String source, String code) {
        for (JsonNode row : set) {
            if (source.equals(row.get("source").asText()) && code.equals(row.get("code").asText())) {
                return row;
            }
        }
        return null;
    }
}
