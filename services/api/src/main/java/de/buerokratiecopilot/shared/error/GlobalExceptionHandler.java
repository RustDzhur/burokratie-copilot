package de.buerokratiecopilot.shared.error;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * Translates every failure into the single API error envelope (spec §20).
 *
 * <p>Guarantees:
 * <ul>
 *   <li>stable machine readable codes,</li>
 *   <li>messages localized to the caller's language,</li>
 *   <li>a correlation ID on every response,</li>
 *   <li>no stack traces, SQL statements, internal class names or data of other tenants
 *       ever leave the server — unexpected failures are logged with the correlation ID and
 *       answered with a generic message (§33: do not leak internals).</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final MessageSource messageSource;

    public GlobalExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApiException(ApiException ex, HttpServletRequest request,
                                                               Locale locale) {
        ErrorCode code = ex.errorCode();
        List<ApiErrorResponse.FieldViolation> violations = ex.violations();
        String message = localize(code.messageKey(), ex.getMessage(), locale);
        log.debug("API error {} on {}: {}", code, request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(code.status())
                .body(ApiErrorResponse.of(code.name(), message, correlationId(), request.getRequestURI(), violations));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex,
                                                             HttpServletRequest request, Locale locale) {
        List<ApiErrorResponse.FieldViolation> violations = new ArrayList<>();
        ex.getBindingResult().getFieldErrors().forEach(error -> violations.add(
                new ApiErrorResponse.FieldViolation(error.getField(), "INVALID", error.getDefaultMessage())));
        ex.getBindingResult().getGlobalErrors().forEach(error -> violations.add(
                new ApiErrorResponse.FieldViolation(null, "INVALID", error.getDefaultMessage())));
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status())
                .body(ApiErrorResponse.of(ErrorCode.VALIDATION_FAILED.name(),
                        localize(ErrorCode.VALIDATION_FAILED.messageKey(), "Validation failed", locale),
                        correlationId(), request.getRequestURI(), violations));
    }

    @ExceptionHandler({ HttpMessageNotReadableException.class,
                        MethodArgumentTypeMismatchException.class,
                        MissingServletRequestParameterException.class })
    public ResponseEntity<ApiErrorResponse> handleMalformedRequest(Exception ex, HttpServletRequest request,
                                                                   Locale locale) {
        // The parse error itself can contain fragments of the payload — never echo it back.
        log.debug("Malformed request on {}: {}", request.getRequestURI(), ex.getMessage());
        return simple(ErrorCode.MALFORMED_BODY, request, locale);
    }

    /**
     * A value the code could not turn into what it needs: an enum word it does not know ({@code valueOf}), an
     * identifier that is not a UUID, a date that is not a date. In this code base those come from the request,
     * so they are the caller's mistake; answering 500 would tell the operator's pager about a typo.
     */
    @ExceptionHandler({ IllegalArgumentException.class, java.time.format.DateTimeParseException.class,
                        org.springframework.data.mapping.PropertyReferenceException.class })
    public ResponseEntity<ApiErrorResponse> handleUnparsableValue(RuntimeException ex, HttpServletRequest request,
                                                                  Locale locale) {
        log.debug("Unusable value on {}: {}", request.getRequestURI(), ex.getMessage());
        return simple(ErrorCode.INVALID_REQUEST, request, locale);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthentication(AuthenticationException ex,
                                                                 HttpServletRequest request, Locale locale) {
        log.debug("Authentication failure on {}: {}", request.getRequestURI(), ex.getMessage());
        return simple(ErrorCode.UNAUTHENTICATED, request, locale);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request,
                                                               Locale locale) {
        // Deliberately no detail: the caller learns that access was denied, not what exists.
        log.info("Access denied on {} (user={}, tenant={})", request.getRequestURI(),
                RequestContext.userId().orElse(null), RequestContext.tenantId().orElse(null));
        return simple(ErrorCode.FORBIDDEN, request, locale);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleTooLarge(MaxUploadSizeExceededException ex,
                                                           HttpServletRequest request, Locale locale) {
        return simple(ErrorCode.PAYLOAD_TOO_LARGE, request, locale);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleIntegrity(DataIntegrityViolationException ex,
                                                            HttpServletRequest request, Locale locale) {
        // Constraint names are useful for operators but are not exposed to the client.
        log.warn("Data integrity violation on {}: {}", request.getRequestURI(), rootMessage(ex));
        return simple(ErrorCode.CONFLICT, request, locale);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorResponse> handleOptimisticLock(OptimisticLockingFailureException ex,
                                                                 HttpServletRequest request, Locale locale) {
        return simple(ErrorCode.OPTIMISTIC_LOCK, request, locale);
    }

    @ExceptionHandler(de.buerokratiecopilot.shared.storage.BlobStorageException.class)
    public ResponseEntity<ApiErrorResponse> handleStorageFailure(
            de.buerokratiecopilot.shared.storage.BlobStorageException ex, HttpServletRequest request,
            Locale locale) {
        // The object store is an external dependency: an operator has to look at it, the client only
        // needs to know that the file could not be stored or read.
        log.error("Object storage failure on {}: {}", request.getRequestURI(), ex.getMessage());
        return simple(ErrorCode.DEPENDENCY_UNAVAILABLE, request, locale);
    }

    @ExceptionHandler(de.buerokratiecopilot.documents.domain.InvalidDocumentStatusTransition.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidTransition(
            de.buerokratiecopilot.documents.domain.InvalidDocumentStatusTransition ex,
            HttpServletRequest request, Locale locale) {
        return ResponseEntity.status(ErrorCode.INVALID_STATE_TRANSITION.status())
                .body(ApiErrorResponse.of(ErrorCode.INVALID_STATE_TRANSITION.name(), ex.getMessage(),
                        correlationId(), request.getRequestURI()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex,
                                                                   HttpServletRequest request, Locale locale) {
        return simple(ErrorCode.METHOD_NOT_ALLOWED, request, locale);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoResource(NoResourceFoundException ex, HttpServletRequest request,
                                                             Locale locale) {
        return simple(ErrorCode.NOT_FOUND, request, locale);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request, Locale locale) {
        // The correlation ID is the only handle the client gets; the detail stays in the log.
        log.error("Unhandled exception on {} [correlationId={}]", request.getRequestURI(), correlationId(), ex);
        return simple(ErrorCode.INTERNAL_ERROR, request, locale);
    }

    private ResponseEntity<ApiErrorResponse> simple(ErrorCode code, HttpServletRequest request, Locale locale) {
        return ResponseEntity.status(code.status())
                .body(ApiErrorResponse.of(code.name(),
                        localize(code.messageKey(), code.name(), locale),
                        correlationId(), request.getRequestURI()));
    }

    private String localize(String key, String fallback, Locale locale) {
        try {
            return messageSource.getMessage(key, null, locale);
        }
        catch (NoSuchMessageException ex) {
            return fallback;
        }
    }

    private String correlationId() {
        return RequestContext.correlationId().orElse(null);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage();
    }
}
