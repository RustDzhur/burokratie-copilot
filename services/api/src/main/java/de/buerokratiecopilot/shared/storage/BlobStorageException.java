package de.buerokratiecopilot.shared.storage;

/**
 * Raised when the storage backend itself fails (unreachable service, I/O error, missing object).
 *
 * <p>Callers must not swallow it: a document whose bytes were not stored must never be reported as
 * successfully received (spec §1.2).</p>
 */
public class BlobStorageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public BlobStorageException(String message) {
        super(message);
    }

    public BlobStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
