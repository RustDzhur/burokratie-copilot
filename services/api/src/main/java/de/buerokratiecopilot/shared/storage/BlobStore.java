package de.buerokratiecopilot.shared.storage;

import java.io.InputStream;
import java.util.UUID;

/**
 * Object storage abstraction (spec §2.1: "S3-compatible object storage with MinIO support for
 * local development").
 *
 * <p>Two implementations exist: {@code S3BlobStore} for any S3-compatible service (MinIO, AWS S3,
 * …) and {@code LocalFsBlobStore} for development and offline deployments. The rest of the
 * application never talks to a storage SDK directly, so switching the backend is a configuration
 * change.</p>
 *
 * <p>Every key is scoped to a tenant by {@link BlobKeys}. Implementations must reject a key that
 * does not belong to the tenant of the caller — one tenant must never be able to address the bytes
 * of another, even if a key leaks through an unrelated bug (spec §3.2).</p>
 */
public interface BlobStore {

    /** Result of a successful write. */
    record StoredBlob(String storageKey, String backend, long sizeBytes, String contentHash) {
    }

    /**
     * Stores a stream.
     *
     * @param tenantId owner of the object; becomes part of the key
     * @param data     content; the caller is responsible for closing it
     * @param sizeBytes exact size, or {@code -1} when unknown
     * @param contentType MIME type as detected by the platform, not as claimed by the client
     */
    StoredBlob store(UUID tenantId, InputStream data, long sizeBytes, String contentType);

    /** Opens a stored object for reading. The caller closes the stream. */
    InputStream retrieve(String storageKey);

    /** Removes an object; missing objects are not an error (idempotent delete). */
    void delete(String storageKey);

    boolean exists(String storageKey);

    /** Identifier of the backend, stored alongside the metadata of every blob. */
    String backendName();

    /** Whether the backend is reachable and correctly configured right now. */
    boolean isAvailable();
}
