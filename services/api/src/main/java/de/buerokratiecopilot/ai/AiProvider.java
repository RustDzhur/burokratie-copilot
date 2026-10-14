package de.buerokratiecopilot.ai;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A model provider, cloud or local (spec §25, E-005).
 *
 * <p>An interface rather than a client, so that which model answers is a deployment decision and not
 * something baked into every caller. The platform has to be able to run with no external AI at all — the
 * specification requires it — and that is only possible if nothing calls a vendor directly.</p>
 *
 * <p>Keys live behind this interface. No caller sees one, no caller passes one, and nothing here returns
 * one: the acceptance criterion is that keys exist only on the backend, and the way to keep that true is
 * for the rest of the code to have no way to obtain them.</p>
 */
public interface AiProvider {

    /** Which provider this is, as the usage records name it. */
    String name();

    /** What is being asked. */
    record Request(UUID tenantId, String jobType, String instructionVersion, String instructionHash,
                   String input, String model, int timeoutSeconds, List<String> images) {

        /** A request without pictures, which is what every caller before the reading of scans was. */
        public Request(UUID tenantId, String jobType, String instructionVersion, String instructionHash,
                       String input, String model, int timeoutSeconds) {
            this(tenantId, jobType, instructionVersion, instructionHash, input, model, timeoutSeconds, List.of());
        }

        public Map<String, Object> asSafeMetadata() {
            // Deliberately without the input and without the pictures: this map goes into the run record and the
            // logs, and the acceptance criterion is that no document content appears there.
            return Map.of("jobType", jobType, "model", model == null ? "" : model,
                    "imageCount", images == null ? 0 : images.size());
        }
    }

    /** What came back. */
    record Completion(String text, Map<String, Object> structured, int inputTokens, int outputTokens,
                      BigDecimal costAmount, String currency, long latencyMs, List<String> warnings) {

        public static Completion of(String text, int inputTokens, int outputTokens) {
            return new Completion(text, Map.of(), inputTokens, outputTokens, BigDecimal.ZERO, "EUR", 0,
                    List.of());
        }
    }

    /** Why a provider did not answer. */
    class ProviderUnavailableException extends RuntimeException {

        private final String code;

        public ProviderUnavailableException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /**
     * Answers a request.
     *
     * @throws ProviderUnavailableException when the provider cannot or will not answer; the caller treats
     *                                      every reason the same way at first — it tries the next one —
     *                                      and records the code so the cause stays visible
     */
    Completion complete(Request request);

    /**
     * The model this provider uses when a caller does not name one. A run is always recorded with the model that answered,
     * never with an empty one: the record is how a bill and a bad answer are traced back.
     */
    default String defaultModel() {
        return "default";
    }

    /** Whether this provider could answer right now, without sending anything. */
    boolean isAvailable();

    /** Cost of one request, for the budget check before it is sent. */
    default BigDecimal estimatedCost(String model, int estimatedInputTokens) {
        return BigDecimal.ZERO;
    }
}
