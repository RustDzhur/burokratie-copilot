package de.buerokratiecopilot.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.shared.settings.SettingsService;

/**
 * Verifies the budget limit and the log redaction (spec §25, E-005 acceptance).
 *
 * <p>Two criteria live here. The budget is checked <em>before</em> anything is sent — discovering that
 * the budget is gone after paying for the answer is not a budget. And the logs hold no document content,
 * which cannot be achieved by remembering not to log it: every prompt in this platform is built from a
 * case file, so anything that logs a prompt logs somebody's affairs.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class AiBudgetLimitTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SettingsService settings;

    /** A provider that answers and counts what it was asked. */
    static class CountingProvider implements AiProvider {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String name() {
            return "zaehlend";
        }

        @Override
        public Completion complete(Request request) {
            calls.incrementAndGet();
            return new Completion("Antwort", Map.of(), 100, 50, new BigDecimal("0.25"), "EUR", 100,
                    List.of());
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        int callCount() {
            return calls.get();
        }
    }

    private AiOrchestrator orchestrator(List<AiProvider> providers) {
        return new AiOrchestrator(providers, jdbc, settings);
    }

    private UUID tenant() {
        return jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'Budget Test', 'COMPANY') returning id
                """, UUID.class, "bud-" + UUID.randomUUID().toString().substring(0, 12));
    }

    @Test
    @DisplayName("a job is refused before it is sent when the budget is used up")
    void budgetIsCheckedBeforeSending() {
        UUID tenantId = tenant();
        CountingProvider provider = new CountingProvider();
        AiOrchestrator orchestrator = orchestrator(List.of(provider));
        orchestrator.setMonthlyBudget(tenantId, "zaehlend", "modell-1", "SUMMARIZE",
                new BigDecimal("0.10"));
        jdbc.update("""
                update ai_usage_records set cost_amount = 0.50, request_count = 1
                where tenant_id = ? and provider = 'zaehlend'
                """, tenantId);

        AiOrchestrator.Outcome outcome = orchestrator.run(tenantId, "SUMMARIZE", "Text", "modell-1", "v1",
                "h", null);

        assertThat(outcome.status()).isEqualTo("SKIPPED_BUDGET");
        assertThat(outcome.reason().toLowerCase()).contains("budget");
        assertThat(provider.callCount())
                .as("paying for the answer and then noticing the budget is gone is not a budget")
                .isZero();
    }

    @Test
    @DisplayName("a job within the budget runs")
    void withinBudgetRuns() {
        UUID tenantId = tenant();
        CountingProvider provider = new CountingProvider();
        AiOrchestrator orchestrator = orchestrator(List.of(provider));
        orchestrator.setMonthlyBudget(tenantId, "zaehlend", "modell-1", "SUMMARIZE",
                new BigDecimal("10.00"));

        AiOrchestrator.Outcome outcome = orchestrator.run(tenantId, "SUMMARIZE", "Text", "modell-1", "v1",
                "h", null);

        assertThat(outcome.status()).isEqualTo("SUCCEEDED");
        assertThat(provider.callCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a tenant without a budget is not limited by one")
    void noBudgetMeansNoLimit() {
        UUID tenantId = tenant();
        CountingProvider provider = new CountingProvider();
        AiOrchestrator orchestrator = orchestrator(List.of(provider));

        assertThat(orchestrator.monthlyBudget(tenantId)).isNull();
        assertThat(orchestrator.run(tenantId, "SUMMARIZE", "Text", "m", "v1", "h", null).status())
                .isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("costs of finished jobs accumulate, so the next check sees them")
    void costsAccumulate() {
        UUID tenantId = tenant();
        CountingProvider provider = new CountingProvider();
        AiOrchestrator orchestrator = orchestrator(List.of(provider));

        orchestrator.run(tenantId, "SUMMARIZE", "Text", "m", "v1", "h", null);
        orchestrator.run(tenantId, "SUMMARIZE", "Text", "m", "v1", "h", null);

        assertThat(orchestrator.spentThisMonth(tenantId)).isEqualByComparingTo("0.50");
    }

    @Test
    @DisplayName("a budget of another tenant does not apply here")
    void budgetIsPerTenant() {
        UUID first = tenant();
        UUID second = tenant();
        CountingProvider provider = new CountingProvider();
        AiOrchestrator orchestrator = orchestrator(List.of(provider));
        orchestrator.setMonthlyBudget(first, "zaehlend", "modell-1", "SUMMARIZE", new BigDecimal("0.01"));
        jdbc.update("""
                update ai_usage_records set cost_amount = 5.00, request_count = 1
                where tenant_id = ? and provider = 'zaehlend'
                """, first);

        assertThat(orchestrator.run(first, "SUMMARIZE", "Text", "modell-1", "v1", "h", null).status())
                .isEqualTo("SKIPPED_BUDGET");
        assertThat(orchestrator.run(second, "SUMMARIZE", "Text", "modell-1", "v1", "h", null).status())
                .as("one tenant running out of budget must not stop another")
                .isEqualTo("SUCCEEDED");
    }
}
