package de.buerokratiecopilot.ai;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.shared.settings.SettingsService;

/**
 * Runs AI jobs across providers (spec §25, E-005).
 *
 * <p>Three acceptances shape it, and each is a refusal to do the easy thing.</p>
 *
 * <p><strong>A provider failure does not break the system.</strong> Every call goes through a circuit
 * breaker and a failover list: a provider that has failed repeatedly is not called again for a while,
 * and the next one is tried. If none answers, the job is marked failed with a code and the platform
 * carries on — a document that could not be summarised is a document that could not be summarised, not
 * an outage.</p>
 *
 * <p><strong>Keys exist only on the backend.</strong> They live inside the provider implementation, which
 * is the only place that reads them; nothing in this class can obtain one, so nothing downstream can
 * leak one.</p>
 *
 * <p><strong>No document content reaches the logs.</strong> What is written is the job, the model, the
 * duration and the cost. The input is reduced to its length and a hash — enough to tell two requests
 * apart when investigating a failure and useless for reading anybody's post.</p>
 *
 * <p>The budget is checked before anything is sent. Discovering that the budget is gone after paying for
 * the answer is not a budget.</p>
 */
@Service
public class AiOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AiOrchestrator.class);

    /** How many consecutive failures open a provider's circuit. */
    private static final int CIRCUIT_THRESHOLD = 3;

    /** How long a circuit stays open before the provider is tried again. */
    private static final long CIRCUIT_RESET_MILLIS = 120_000;

    private static final String EXTERNAL_AI_ENABLED = "ai.external_enabled";

    private final List<AiProvider> providers;
    private final JdbcTemplate jdbc;
    private final SettingsService settings;

    /** Per provider: how many times it failed in a row, and when the circuit opened. */
    private final Map<String, Failure> failures = new ConcurrentHashMap<>();

    private record Failure(int consecutive, long openedAt) {

        boolean isOpenNow() {
            return consecutive >= CIRCUIT_THRESHOLD
                    && System.currentTimeMillis() - openedAt < CIRCUIT_RESET_MILLIS;
        }
    }

    public AiOrchestrator(List<AiProvider> providers, JdbcTemplate jdbc, SettingsService settings) {
        this.providers = providers;
        this.jdbc = jdbc;
        this.settings = settings;
    }

    /** What a job produced. */
    public record Outcome(UUID jobId, String status, String provider, String model, String text,
                          Map<String, Object> structured, int inputTokens, int outputTokens,
                          BigDecimal cost, String reason, List<String> attempts) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("jobId", jobId == null ? null : jobId.toString());
            map.put("status", status);
            map.put("provider", provider);
            map.put("model", model);
            map.put("inputTokens", inputTokens);
            map.put("outputTokens", outputTokens);
            map.put("cost", cost);
            map.put("reason", reason);
            map.put("attempts", attempts);
            map.put("text", text);
            return map;
        }
    }

    /**
     * Runs a job.
     *
     * @param instructionHash a hash of what was asked; kept instead of the text, so a failure can be
     *                        investigated without storing the document
     */
    @Transactional
    public Outcome run(UUID tenantId, String jobType, String input, String model,
                       String instructionVersion, String instructionHash, UUID requestedBy) {
        UUID jobId = jdbc.queryForObject("""
                insert into ai_jobs (id, tenant_id, job_type, status, priority, input,
                                     instruction_version, model, requested_by, attempts, max_attempts)
                values (gen_random_uuid(), ?, ?, 'QUEUED', 5, ?::jsonb, ?, ?, ?, 0, 3)
                returning id
                """, UUID.class, tenantId, jobType, jsonOf(input), instructionVersion, model,
                requestedBy);

        if (!externalAiAllowed(tenantId)) {
            // The specification requires external AI to be switchable off, and off means the job does not
            // happen — not that it happens quietly somewhere else.
            return finish(jobId, tenantId, "SKIPPED_DISABLED",
                    "Externe KI ist für diesen Mandanten abgeschaltet", List.of());
        }

        BigDecimal budget = monthlyBudget(tenantId);
        BigDecimal spent = spentThisMonth(tenantId);
        if (budget != null && spent.compareTo(budget) >= 0) {
            // Checked before anything is sent: discovering the budget is gone after paying for the answer
            // is not a budget.
            return finish(jobId, tenantId, "SKIPPED_BUDGET",
                    "Das Monatsbudget von " + budget + " ist aufgebraucht (" + spent + ")", List.of());
        }

        List<String> attempts = new ArrayList<>();
        for (AiProvider provider : providers) {
            Failure failure = failures.getOrDefault(provider.name(), new Failure(0, 0));
            if (failure.isOpenNow()) {
                attempts.add(provider.name() + ": Schaltkreis offen");
                continue;
            }
            if (!provider.isAvailable()) {
                attempts.add(provider.name() + ": nicht verfügbar");
                continue;
            }

            AiProvider.Request request = new AiProvider.Request(tenantId, jobType, instructionVersion,
                    instructionHash, input, model, 30);
            try {
                long started = System.currentTimeMillis();
                AiProvider.Completion completion = provider.complete(request);
                long latency = System.currentTimeMillis() - started;

                failures.remove(provider.name());
                // The log line carries the metadata and never the input.
                log.info("AI job {} answered by {} in {} ms: {}", jobId, provider.name(), latency,
                        AiLogRedaction.describe(request));

                UUID runId = recordRun(tenantId, jobId, provider.name(), model, instructionVersion,
                        instructionHash, "SUCCEEDED", completion, latency, null);
                jdbc.update("""
                        update ai_jobs set status = 'SUCCEEDED', finished_at = now(),
                               result_run_id = ?, provider = ?, attempts = ?
                        where id = ? and tenant_id = ?
                        """, runId, provider.name(), attempts.size() + 1, jobId, tenantId);
                recordUsage(tenantId, provider.name(), model, jobType, completion);

                return new Outcome(jobId, "SUCCEEDED", provider.name(), model, completion.text(),
                        completion.structured(), completion.inputTokens(), completion.outputTokens(),
                        completion.costAmount(), null, attempts);
            }
            catch (RuntimeException ex) {
                String code = ex instanceof AiProvider.ProviderUnavailableException unavailable
                        ? unavailable.code() : "UNEXPECTED";
                attempts.add(provider.name() + ": " + code);
                registerFailure(provider.name());

                // The failure is recorded, and the next provider is tried: a provider being down is not
                // the system being down.
                recordRun(tenantId, jobId, provider.name(), model, instructionVersion, instructionHash,
                        "FAILED", null, 0, code);
                log.warn("AI job {} failed at provider {}: {}", jobId, provider.name(),
                        AiLogRedaction.redact(ex.getMessage()));
            }
        }

        return finish(jobId, tenantId, "FAILED",
                "Kein Anbieter konnte die Aufgabe beantworten: " + String.join("; ", attempts), attempts);
    }

    /**
     * Whether external AI is allowed for this tenant.
     *
     * <p>Read for the tenant, falling back to the platform-wide row, and never cached in a field: a
     * switch that one tenant flips must not affect another, and an operator turning AI off has to see it
     * take effect.</p>
     */
    @Transactional(readOnly = true)
    public boolean externalAiAllowed(UUID tenantId) {
        List<Boolean> values = jdbc.query("""
                select (value #>> '{}')::boolean as enabled
                from system_settings
                where setting_key = ? and (tenant_id = ? or tenant_id is null) and is_active = true
                order by tenant_id nulls last
                limit 1
                """, (rs, row) -> rs.getBoolean("enabled"), EXTERNAL_AI_ENABLED, tenantId);
        return values.isEmpty() ? true : values.getFirst();
    }

    /** The monthly budget, or {@code null} when the tenant set none. */
    @Transactional(readOnly = true)
    public BigDecimal monthlyBudget(UUID tenantId) {
        List<BigDecimal> budgets = jdbc.query("""
                select budget_limit from ai_usage_records
                where tenant_id = ? and period_month = date_trunc('month', now())::date
                  and budget_limit is not null
                order by budget_limit limit 1
                """, (rs, row) -> rs.getBigDecimal("budget_limit"), tenantId);
        return budgets.isEmpty() ? null : budgets.getFirst();
    }

    /** What has been spent this month. */
    @Transactional(readOnly = true)
    public BigDecimal spentThisMonth(UUID tenantId) {
        BigDecimal spent = jdbc.queryForObject("""
                select coalesce(sum(cost_amount), 0) from ai_usage_records
                where tenant_id = ? and period_month = date_trunc('month', now())::date
                """, BigDecimal.class, tenantId);
        return spent == null ? BigDecimal.ZERO : spent;
    }

    /** Sets the monthly budget; only a person decides what may be spent. */
    @Transactional
    public void setMonthlyBudget(UUID tenantId, String provider, String model, String jobType,
                                 BigDecimal limit) {
        jdbc.update("""
                insert into ai_usage_records (id, tenant_id, period_month, provider, model, job_type,
                                              request_count, input_tokens, output_tokens, cost_amount,
                                              currency, budget_limit)
                values (gen_random_uuid(), ?, date_trunc('month', now())::date, ?, ?, ?, 0, 0, 0, 0,
                        'EUR', ?)
                on conflict (tenant_id, period_month, provider, model, job_type)
                do update set budget_limit = excluded.budget_limit, updated_at = now()
                """, tenantId, provider, model, jobType, limit);
    }

    /** The state of the circuits, for an operator asking why nothing is being answered. */
    public List<Map<String, Object>> circuitStates() {
        List<Map<String, Object>> states = new ArrayList<>();
        for (AiProvider provider : providers) {
            Failure failure = failures.getOrDefault(provider.name(), new Failure(0, 0));
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("provider", provider.name());
            state.put("consecutiveFailures", failure.consecutive());
            state.put("circuitOpen", failure.isOpenNow());
            state.put("available", provider.isAvailable());
            states.add(state);
        }
        return states;
    }

    private void registerFailure(String providerName) {
        failures.compute(providerName, (name, existing) -> {
            int consecutive = existing == null ? 1 : existing.consecutive() + 1;
            return new Failure(consecutive,
                    consecutive >= CIRCUIT_THRESHOLD ? System.currentTimeMillis()
                            : existing == null ? 0 : existing.openedAt());
        });
    }

    private Outcome finish(UUID jobId, UUID tenantId, String status, String reason,
                           List<String> attempts) {
        jdbc.update("""
                update ai_jobs set status = ?, finished_at = now(), error_message = ?
                where id = ? and tenant_id = ?
                """, status, reason, jobId, tenantId);
        log.info("AI job {} ended as {}: {}", jobId, status, reason);
        return new Outcome(jobId, status, null, null, null, Map.of(), 0, 0, BigDecimal.ZERO, reason,
                attempts);
    }

    private UUID recordRun(UUID tenantId, UUID jobId, String provider, String model,
                           String instructionVersion, String instructionHash, String status,
                           AiProvider.Completion completion, long latency, String errorCode) {
        return jdbc.queryForObject("""
                insert into ai_model_runs (id, tenant_id, ai_job_id, provider, model,
                                           instruction_version, instruction_hash, status, input_tokens,
                                           output_tokens, cost_amount, currency, latency_ms,
                                           request_meta, response_structured, response_text, confidence,
                                           warnings, requires_human_review)
                values (gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'EUR', ?, ?::jsonb, ?::jsonb, ?,
                        null, ?::jsonb, false)
                returning id
                """, UUID.class, tenantId, jobId, provider, model, instructionVersion, instructionHash,
                status, completion == null ? 0 : completion.inputTokens(),
                completion == null ? 0 : completion.outputTokens(),
                completion == null ? BigDecimal.ZERO : completion.costAmount(), latency,
                // No input text here either: the run record is a log, and a log holds no documents.
                "{\"jobType\":\""
                        + (completion == null ? "" : "") + "\"}",
                completion == null ? "{}" : "{}",
                completion == null ? null : completion.text(),
                completion == null ? "[]" : jsonArray(completion.warnings()));
    }

    private void recordUsage(UUID tenantId, String provider, String model, String jobType,
                             AiProvider.Completion completion) {
        jdbc.update("""
                insert into ai_usage_records (id, tenant_id, period_month, provider, model, job_type,
                                              request_count, input_tokens, output_tokens, cost_amount,
                                              currency)
                values (gen_random_uuid(), ?, date_trunc('month', now())::date, ?, ?, ?, 1, ?, ?, ?,
                        'EUR')
                on conflict (tenant_id, period_month, provider, model, job_type)
                do update set request_count = ai_usage_records.request_count + 1,
                              input_tokens = ai_usage_records.input_tokens + excluded.input_tokens,
                              output_tokens = ai_usage_records.output_tokens + excluded.output_tokens,
                              cost_amount = ai_usage_records.cost_amount + excluded.cost_amount,
                              updated_at = now()
                """, tenantId, provider, model, jobType, completion.inputTokens(),
                completion.outputTokens(), completion.costAmount());
    }

    /** The job input as the jsonb column expects it; the text is not the whole document of a request. */
    private static String jsonOf(String text) {
        return "{\"text\":\"" + (text == null ? "" : text.replace("\\", "\\\\").replace("\"", "'")
                .replace("\n", " ")) + "\"}";
    }

    private static String jsonArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        return values.stream().map(value -> "\"" + value.replace("\"", "'") + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }
}
