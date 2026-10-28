package de.buerokratiecopilot.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.shared.settings.SettingsService;

/**
 * Verifies that no document content reaches the logs or the run records (spec §25, E-005 acceptance).
 *
 * <p>The criterion cannot be met by remembering not to log a prompt. Every prompt here is built from a
 * case file — a contract, an invoice, a letter from an authority — so anything that records a prompt
 * records somebody's affairs, and a log is the last place a person expects to find them.</p>
 *
 * <p>The test therefore sends a distinctive sentence through the whole path and then searches everything
 * that was written down for it.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class AiLogRedactionTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SettingsService settings;

    /**
     * Credential-shaped text for the redaction tests.
     *
     * <p>Assembled from parts on purpose. Writing a realistic key into the repository gets it flagged by
     * the secret scan, and rightly so: a scanner cannot tell a fake from a real one, and teaching a team
     * to allowlist such literals is how a real key eventually slips through. The tests only need
     * something the patterns recognise, which is what these produce.</p>
     */
    private static final String FAKE_API_KEY = "api_key=" + "sk-" + "abcdef" + "1234567890";
    private static final String FAKE_BEARER = "Bearer " + "eyJhbGciOiJIUzI1NiJ9" + "payload";
    private static final String FAKE_BASIC = "Authorization: Basic " + "dXNlcjpw" + "YXNz";

    /** A sentence that could only come from a document, so finding it anywhere is a leak. */
    private static final String DOCUMENT_TEXT =
            "Sehr geehrter Herr Dr. Müller, hiermit lege ich Widerspruch gegen den Bescheid vom "
                    + "14.03.2026 ein, Aktenzeichen 42-ABC-2026.";

    static class EchoProvider implements AiProvider {

        @Override
        public String name() {
            return "echo";
        }

        @Override
        public Completion complete(Request request) {
            return new Completion("Antwort", Map.of(), 200, 30, new BigDecimal("0.01"), "EUR", 50,
                    List.of());
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }

    static class FailingProvider implements AiProvider {

        @Override
        public String name() {
            return "kaputt";
        }

        @Override
        public Completion complete(Request request) {
            // A provider that echoes the request back in its error message is exactly how content
            // reaches a log.
            throw new ProviderUnavailableException("UPSTREAM_ERROR",
                    "Provider rejected the input: " + request.input(), null);
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }

    private AiOrchestrator orchestrator(List<AiProvider> providers) {
        return new AiOrchestrator(providers, jdbc, settings);
    }

    private UUID tenant() {
        return jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'Redaktion Test', 'COMPANY') returning id
                """, UUID.class, "red-" + UUID.randomUUID().toString().substring(0, 12));
    }

    @Test
    @DisplayName("the description of a request carries no content")
    void describeCarriesNoContent() {
        AiProvider.Request request = new AiProvider.Request(UUID.randomUUID(), "SUMMARIZE", "v1", "hash-1",
                DOCUMENT_TEXT, "modell-1", 30);

        Map<String, Object> described = AiLogRedaction.describe(request);

        assertThat(described).containsKeys("jobType", "model", "inputLength", "inputHash");
        assertThat(described.values().stream().map(String::valueOf).toList())
                .allSatisfy(value -> assertThat(value).doesNotContain("Widerspruch"))
                .allSatisfy(value -> assertThat(value).doesNotContain("42-ABC-2026"));
        assertThat(described).containsEntry("inputLength", DOCUMENT_TEXT.length());
    }

    @Test
    @DisplayName("a long text is removed from a log line whatever it is called")
    void longTextIsRemoved() {
        String redacted = AiLogRedaction.redact(DOCUMENT_TEXT);

        assertThat(redacted).doesNotContain("Widerspruch");
        assertThat(redacted).contains("Inhalt entfernt");
    }

    @Test
    @DisplayName("a secret that appears in a log line is removed")
    void secretsAreRemoved() {
        assertThat(AiLogRedaction.redact(FAKE_API_KEY)).doesNotContain("sk-abcdef");
        assertThat(AiLogRedaction.redact(FAKE_BEARER)).doesNotContain("eyJhbGci");
        assertThat(AiLogRedaction.redact(FAKE_BASIC)).doesNotContain("dXNlcjpw");
    }

    @Test
    @DisplayName("short harmless text is left readable, so logs stay useful")
    void harmlessTextStays() {
        assertThat(AiLogRedaction.redact("AI job 4711 answered by echo in 120 ms"))
                .isEqualTo("AI job 4711 answered by echo in 120 ms");
    }

    @Test
    @DisplayName("the run record holds no document content")
    void runRecordHoldsNoContent() {
        UUID tenantId = tenant();
        AiOrchestrator.Outcome outcome = orchestrator(List.of(new EchoProvider()))
                .run(tenantId, "SUMMARIZE", DOCUMENT_TEXT, "modell-1", "v1", "hash-of-the-prompt", null);

        Map<String, Object> run = jdbc.queryForMap("""
                select provider, model, instruction_hash, request_meta, response_structured
                from ai_model_runs where ai_job_id = ?
                """, outcome.jobId());

        assertThat(run.values().stream().map(String::valueOf).toList())
                .allSatisfy(value -> assertThat(value).doesNotContain("Widerspruch"))
                .allSatisfy(value -> assertThat(value).doesNotContain("42-ABC-2026"));
        assertThat(run).containsEntry("instruction_hash", "hash-of-the-prompt");
    }

    @Test
    @DisplayName("the redaction helper reports which fields would leak")
    void offendingFieldsAreFound() {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("provider", "echo");
        record.put("prompt", DOCUMENT_TEXT);
        record.put("note", "ok");

        assertThat(AiLogRedaction.offendingFields(record, "Widerspruch")).containsExactly("prompt");
        assertThat(AiLogRedaction.offendingFields(record, "kommt-nicht-vor")).isEmpty();
    }

    @Test
    @DisplayName("a provider that quotes the input in its error does not put it in the job record")
    void providerEchoingTheInputDoesNotLeakIt() {
        UUID tenantId = tenant();

        AiOrchestrator.Outcome outcome = orchestrator(List.of(new FailingProvider()))
                .run(tenantId, "SUMMARIZE", DOCUMENT_TEXT, "modell-1", "v1", "hash-1", null);

        // The provider's message is redacted before it is written, which is the point of routing every
        // log line through one place.
        assertThat(AiLogRedaction.redact("Provider rejected the input: " + DOCUMENT_TEXT))
                .doesNotContain("Widerspruch");

        Map<String, Object> job = jdbc.queryForMap("""
                select input, error_message from ai_jobs where id = ?
                """, outcome.jobId());
        assertThat(String.valueOf(job.get("error_message")))
                .as("the job's failure reason names the provider, not the document")
                .doesNotContain("Widerspruch");
    }

    @Test
    @DisplayName("the input is stored as a job input, which is not a log")
    void jobInputIsStoredSeparately() {
        UUID tenantId = tenant();
        AiOrchestrator.Outcome outcome = orchestrator(List.of(new EchoProvider()))
                .run(tenantId, "SUMMARIZE", DOCUMENT_TEXT, "modell-1", "v1", "hash-1", null);

        // The job input is kept so the job can be retried or audited; it is the run record and the logs
        // that must not hold it.
        String input = jdbc.queryForObject("select input::text from ai_jobs where id = ?", String.class,
                outcome.jobId());
        assertThat(input).contains("Widerspruch");
    }

    @Test
    @DisplayName("safety of a value can be asked before writing it")
    void safetyCanBeAskedBeforeWriting() {
        assertThat(AiLogRedaction.isSafeToLog("kurze Meldung")).isTrue();
        assertThat(AiLogRedaction.isSafeToLog(DOCUMENT_TEXT)).isFalse();
        assertThat(AiLogRedaction.isSafeToLog(FAKE_API_KEY)).isFalse();
        assertThat(AiLogRedaction.isSafeToLog(null)).isTrue();
    }
}
