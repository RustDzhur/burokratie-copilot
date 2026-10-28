package de.buerokratiecopilot.shared.error;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The single error envelope of the public API (spec §20).
 *
 * <p>It always contains a stable machine readable {@code code}, a human readable
 * {@code message} localized to the request language, and the {@code correlationId} that ties
 * the response to the server log. {@code details} only ever carries safe, non-sensitive
 * information — never stack traces, SQL statements or internal identifiers of other tenants.</p>
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiErrorResponse(
        String code,
        String message,
        String correlationId,
        String path,
        java.time.Instant timestamp,
        List<FieldViolation> details) {

    public record FieldViolation(String field, String code, String message) {
    }

    public static ApiErrorResponse of(String code, String message, String correlationId, String path) {
        return new ApiErrorResponse(code, message, correlationId, path, java.time.Instant.now(), List.of());
    }

    public static ApiErrorResponse of(String code, String message, String correlationId, String path,
                                      List<FieldViolation> details) {
        return new ApiErrorResponse(code, message, correlationId, path, java.time.Instant.now(), details);
    }
}
