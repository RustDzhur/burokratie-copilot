package de.buerokratiecopilot.shared.error;

import java.io.Serial;
import java.util.List;

/**
 * Base class for expected, business-level failures.
 *
 * <p>These exceptions always carry an {@link ErrorCode} and an optional message key with
 * arguments, so the API can answer in the language of the caller. They deliberately do not
 * carry stack-trace-based detail to the client.</p>
 */
public class ApiException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;
    private final transient List<ApiErrorResponse.FieldViolation> violations;

    public ApiException(ErrorCode errorCode, String message) {
        this(errorCode, message, List.of());
    }

    public ApiException(ErrorCode errorCode, String message, List<ApiErrorResponse.FieldViolation> violations) {
        super(message);
        this.errorCode = errorCode;
        this.violations = violations == null ? List.of() : List.copyOf(violations);
    }

    public ApiException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.violations = List.of();
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public List<ApiErrorResponse.FieldViolation> violations() {
        return violations;
    }

    // Convenience factories keep controllers and services free of repetitive boilerplate.

    public static ApiException notFound(String what) {
        return new ApiException(ErrorCode.NOT_FOUND, what + " was not found");
    }

    public static ApiException forbidden(String message) {
        return new ApiException(ErrorCode.FORBIDDEN, message);
    }

    public static ApiException insufficientPermissions(String permission) {
        return new ApiException(ErrorCode.INSUFFICIENT_PERMISSIONS, "Missing permission: " + permission);
    }

    public static ApiException conflict(String message) {
        return new ApiException(ErrorCode.CONFLICT, message);
    }

    public static ApiException duplicate(String message) {
        return new ApiException(ErrorCode.DUPLICATE_RESOURCE, message);
    }

    public static ApiException invalidState(String message) {
        return new ApiException(ErrorCode.INVALID_STATE_TRANSITION, message);
    }

    public static ApiException validation(String message, List<ApiErrorResponse.FieldViolation> violations) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message, violations);
    }

    public static ApiException featureDisabled(String feature) {
        return new ApiException(ErrorCode.FEATURE_DISABLED, "Feature is not available: " + feature);
    }
}
