package de.buerokratiecopilot.shared.error;

import org.springframework.http.HttpStatus;

/**
 * Stable error codes of the API.
 *
 * <p>Every code has a matching message key in {@code i18n/messages_*.properties}, so a client
 * receives a stable machine readable code together with a message localized into German,
 * English or Russian (spec §20, §24).</p>
 */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "error.validation"),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "error.invalid_request"),
    MALFORMED_BODY(HttpStatus.BAD_REQUEST, "error.malformed_body"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "error.unauthenticated"),
    SESSION_EXPIRED(HttpStatus.UNAUTHORIZED, "error.session_expired"),
    MFA_REQUIRED(HttpStatus.UNAUTHORIZED, "error.mfa_required"),
    MFA_INVALID(HttpStatus.UNAUTHORIZED, "error.mfa_invalid"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "error.forbidden"),
    INSUFFICIENT_PERMISSIONS(HttpStatus.FORBIDDEN, "error.insufficient_permissions"),
    ACCOUNT_LOCKED(HttpStatus.LOCKED, "error.account_locked"),
    EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN, "error.email_not_verified"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "error.not_found"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "error.method_not_allowed"),
    CONFLICT(HttpStatus.CONFLICT, "error.conflict"),
    /**
     * Mail is a deliberate, optional part of a deployment; a letter that cannot leave the house is not
     * an internal error. The conflict says what is missing in the office's language and leaves the draft
     * exactly where it was, so the person can configure mail and send it afterwards.
     */
    MAIL_NOT_CONFIGURED(HttpStatus.CONFLICT, "error.mail_not_configured"),
    /** The client has no address to write to: a business fact, not a server failure. */
    MANDANT_EMAIL_MISSING(HttpStatus.CONFLICT, "error.mandant_email_missing"),
    DUPLICATE_RESOURCE(HttpStatus.CONFLICT, "error.duplicate_resource"),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT, "error.invalid_state_transition"),
    OPTIMISTIC_LOCK(HttpStatus.CONFLICT, "error.optimistic_lock"),
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "error.payload_too_large"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "error.unsupported_media_type"),
    FILE_REJECTED(HttpStatus.UNPROCESSABLE_ENTITY, "error.file_rejected"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "error.rate_limited"),
    FEATURE_DISABLED(HttpStatus.SERVICE_UNAVAILABLE, "error.feature_disabled"),
    DEPENDENCY_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "error.dependency_unavailable"),
    AI_DISABLED(HttpStatus.SERVICE_UNAVAILABLE, "error.ai_disabled"),
    SUBSCRIPTION_REQUIRED(HttpStatus.PAYMENT_REQUIRED, "error.subscription_required"),
    PLAN_LIMIT_REACHED(HttpStatus.PAYMENT_REQUIRED, "error.plan_limit_reached"),
    BUDGET_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "error.budget_exceeded"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "error.internal");

    private final HttpStatus status;
    private final String messageKey;

    ErrorCode(HttpStatus status, String messageKey) {
        this.status = status;
        this.messageKey = messageKey;
    }

    public HttpStatus status() {
        return status;
    }

    public String messageKey() {
        return messageKey;
    }
}
