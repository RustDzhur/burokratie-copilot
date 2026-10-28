package de.buerokratiecopilot.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.support.TenantContextExtension;

import de.buerokratiecopilot.shared.settings.SettingsService;

/**
 * Verifies that a provider failure does not break the platform (spec §25, E-005 acceptance).
 *
 * <p>The criterion has two halves, and the tests cover both: a failing provider must not stop the work
 * while another one can do it, and when none can, the job must end as a failed job rather than as an
 * exception that travels up through whatever called it. A document that could not be summarised is a
 * document that could not be summarised, not an outage.</p>
 */
@SpringBootTest
@ExtendWith(TenantContextExtension.class)
@ActiveProfiles("test")
class AiProviderFailoverTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SettingsService settings;

    /** A provider that always fails, counting how often it was asked. */
    static class FailingProvider implements AiProvider {

        private final String name;
        private final AtomicInteger calls = new AtomicInteger();

        FailingProvider(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Completion complete(Request request) {
            calls.incrementAndGet();
            throw new ProviderUnavailableException("CONNECTION_REFUSED", "Der Anbieter antwortet nicht",
                    null);
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        int callCount() {
            return calls.get();
        }
    }

    /** A provider that answers. */
    static class WorkingProvider implements AiProvider {

        private final String name;
        private final AtomicInteger calls = new AtomicInteger();

        WorkingProvider(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Completion complete(Request request) {
            calls.incrementAndGet();
            return new Completion("Zusammenfassung", Map.of("summary", "Zusammenfassung"), 120, 40,
                    new BigDecimal("0.0035"), "EUR", 250, List.of());
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
        UUID tenantId = jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'KI Test', 'COMPANY') returning id
                """, UUID.class, "ai-" + UUID.randomUUID().toString().substring(0, 12));
        // Established for the rest of the test: roles and settings live in tables protected by a policy on
        // tenant_id, which the HTTP layer supplies in production.
        RequestContext.setTenantId(tenantId);
        return tenantId;
    }

    @Test
    @DisplayName("a failing provider is followed by a working one, and the job succeeds")
    void failoverKeepsTheJobAlive() {
        UUID tenantId = tenant();
        FailingProvider broken = new FailingProvider("kaputt");
        WorkingProvider working = new WorkingProvider("funktioniert");

        AiOrchestrator.Outcome outcome = orchestrator(List.of(broken, working))
                .run(tenantId, "SUMMARIZE", "Vertragstext", "modell-1", "v1", "hash-1", null);

        assertThat(outcome.status()).isEqualTo("SUCCEEDED");
        assertThat(outcome.provider()).isEqualTo("funktioniert");
        assertThat(outcome.text()).isEqualTo("Zusammenfassung");
        assertThat(outcome.attempts())
                .as("the reason the first one was skipped stays visible")
                .anySatisfy(attempt -> assertThat(attempt).contains("kaputt", "CONNECTION_REFUSED"));
    }

    @Test
    @DisplayName("when every provider fails the job fails, and nothing is thrown at the caller")
    void allProvidersFailingIsAResultNotAnException() {
        UUID tenantId = tenant();

        AiOrchestrator.Outcome outcome = orchestrator(List.of(new FailingProvider("a"),
                new FailingProvider("b")))
                .run(tenantId, "SUMMARIZE", "Vertragstext", "modell-1", "v1", "hash-1", null);

        assertThat(outcome.status()).isEqualTo("FAILED");
        assertThat(outcome.reason()).contains("Kein Anbieter");
        assertThat(outcome.attempts()).hasSize(2);
        assertThat(jdbc.queryForObject("select status from ai_jobs where id = ?", String.class,
                outcome.jobId())).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("a provider that keeps failing is not called again for a while")
    void circuitOpensAfterRepeatedFailures() {
        UUID tenantId = tenant();
        FailingProvider broken = new FailingProvider("kaputt");
        AiOrchestrator orchestrator = orchestrator(List.of(broken));

        for (int attempt = 0; attempt < 4; attempt++) {
            orchestrator.run(tenantId, "SUMMARIZE", "Text", "m", "v1", "h", null);
        }

        // Three failures open the circuit; the fourth call must not reach the provider at all.
        assertThat(broken.callCount())
                .as("a provider that is down should not be asked again and again")
                .isEqualTo(3);
        assertThat(orchestrator.circuitStates()).anySatisfy(state -> assertThat(state)
                .containsEntry("provider", "kaputt")
                .containsEntry("circuitOpen", true)
                .containsEntry("consecutiveFailures", 3));
    }

    @Test
    @DisplayName("a success closes the circuit again")
    void successResetsTheFailures() {
        UUID tenantId = tenant();
        FailingProvider broken = new FailingProvider("kaputt");
        WorkingProvider working = new WorkingProvider("funktioniert");
        AiOrchestrator orchestrator = orchestrator(List.of(broken, working));

        orchestrator.run(tenantId, "SUMMARIZE", "Text", "m", "v1", "h", null);

        assertThat(orchestrator.circuitStates()).anySatisfy(state -> assertThat(state)
                .as("the failing provider has not failed enough times to be cut off")
                .containsEntry("provider", "kaputt")
                .containsEntry("consecutiveFailures", 1)
                .containsEntry("circuitOpen", false));
    }

    @Test
    @DisplayName("a provider that reports itself unavailable is skipped without being called")
    void unavailableProviderIsSkipped() {
        UUID tenantId = tenant();
        AtomicInteger calls = new AtomicInteger();
        AiProvider unavailable = new AiProvider() {
            @Override
            public String name() {
                return "nicht-verfuegbar";
            }

            @Override
            public Completion complete(Request request) {
                calls.incrementAndGet();
                return Completion.of("sollte nicht passieren", 0, 0);
            }

            @Override
            public boolean isAvailable() {
                return false;
            }
        };
        WorkingProvider working = new WorkingProvider("funktioniert");

        AiOrchestrator.Outcome outcome = orchestrator(List.of(unavailable, working))
                .run(tenantId, "SUMMARIZE", "Text", "m", "v1", "h", null);

        assertThat(outcome.status()).isEqualTo("SUCCEEDED");
        assertThat(calls.get())
                .as("asking a provider that says it cannot answer wastes time and money")
                .isZero();
    }

    @Test
    @DisplayName("the run record holds the metadata and the failure code")
    void theRunRecordHoldsTheFailure() {
        UUID tenantId = tenant();
        AiOrchestrator.Outcome outcome = orchestrator(List.of(new FailingProvider("kaputt")))
                .run(tenantId, "SUMMARIZE", "Text", "modell-1", "v1", "hash-1", null);

        Map<String, Object> run = jdbc.queryForMap("""
                select provider, model, status, instruction_hash from ai_model_runs where ai_job_id = ?
                """, outcome.jobId());

        assertThat(run).containsEntry("provider", "kaputt")
                .containsEntry("status", "FAILED")
                .containsEntry("instruction_hash", "hash-1");
    }

    @Test
    @DisplayName("a successful run records its tokens and cost")
    void usageIsRecorded() {
        UUID tenantId = tenant();
        AiOrchestrator.Outcome outcome = orchestrator(List.of(new WorkingProvider("funktioniert")))
                .run(tenantId, "SUMMARIZE", "Text", "modell-1", "v1", "h", null);

        assertThat(outcome.inputTokens()).isEqualTo(120);
        assertThat(outcome.outputTokens()).isEqualTo(40);
        var usage = jdbc.queryForMap("""
                select request_count, input_tokens, cost_amount from ai_usage_records
                where tenant_id = ? and provider = 'funktioniert'
                """, tenantId);
        assertThat(((Number) usage.get("request_count")).intValue()).isEqualTo(1);
        assertThat(((Number) usage.get("input_tokens")).intValue()).isEqualTo(120);
    }

    @Test
    @DisplayName("external AI can be switched off, and then nothing is sent")
    void externalAiCanBeDisabled() {
        UUID tenantId = tenant();
        WorkingProvider working = new WorkingProvider("funktioniert");
        AiOrchestrator orchestrator = orchestrator(List.of(working));

        // The setting is per tenant, and the platform reads it before it does anything else.
        AiOrchestrator.Outcome outcome = orchestrator.run(tenantId, "SUMMARIZE", "Text", "m", "v1", "h",
                null);
        assertThat(outcome.status()).isEqualTo("SUCCEEDED");

        // system_settings is protected by a policy that lets a global row through and needs a tenant for
        // the rest, so this write names the account it belongs to.
        RequestContext.runAs(tenantId, () -> jdbc.update("""
                insert into system_settings (id, tenant_id, setting_key, category, value, value_type,
                                             description, requires_verification, version, is_active)
                values (gen_random_uuid(), ?, 'ai.external_enabled', 'AI', 'false'::jsonb, 'BOOLEAN',
                        '{"de":"Externe KI erlauben"}'::jsonb, false, 1, true)
                """, tenantId));
        AiOrchestrator.Outcome disabled = orchestrator.run(tenantId, "SUMMARIZE", "Text", "m", "v1", "h",
                null);

        assertThat(disabled.status())
                .as("off means the job does not happen, not that it happens quietly elsewhere")
                .isEqualTo("SKIPPED_DISABLED");
    }
}
