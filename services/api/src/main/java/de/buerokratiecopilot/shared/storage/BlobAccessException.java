package de.buerokratiecopilot.shared.storage;

/**
 * Raised when an object key is malformed or does not belong to the tenant of the caller.
 *
 * <p>It maps to HTTP 404 rather than 403 on purpose: telling a caller that an object exists but
 * belongs to somebody else is already an information leak (spec §3.2).</p>
 */
public class BlobAccessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public BlobAccessException(String message) {
        super(message);
    }

    public BlobAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
