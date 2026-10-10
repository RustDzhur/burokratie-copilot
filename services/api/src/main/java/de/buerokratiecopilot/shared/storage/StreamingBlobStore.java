package de.buerokratiecopilot.shared.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Shared streaming logic of the storage backends.
 *
 * <p>Both backends receive a stream of unknown length and must produce a verifiable object with a
 * content hash. The stream is therefore spooled to a temporary file on the application host while
 * the SHA-256 and the exact size are computed, and only then published. That keeps memory usage
 * independent of the document size (spec §27) and gives every backend the same key layout, which
 * in turn makes the storage backend a configuration detail rather than an architectural one.</p>
 */
public abstract class StreamingBlobStore implements BlobStore {

    @Override
    public final StoredBlob store(UUID tenantId, InputStream data, long declaredSize, String contentType) {
        if (tenantId == null) {
            throw new IllegalArgumentException("A blob requires an owning tenant");
        }
        Path temporary = null;
        try {
            Path temporaryDirectory = temporaryDirectory();
            Files.createDirectories(temporaryDirectory);
            temporary = Files.createTempFile(temporaryDirectory, "upload-", ".part");

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long written;
            try (DigestInputStream digesting = new DigestInputStream(data, digest);
                 OutputStream out = Files.newOutputStream(temporary)) {
                written = digesting.transferTo(out);
            }

            if (declaredSize >= 0 && written != declaredSize) {
                // A truncated or padded upload must never be accepted silently.
                throw new BlobStorageException(
                        "Uploaded size %d does not match the declared size %d".formatted(written, declaredSize));
            }

            String hash = HexFormat.of().formatHex(digest.digest());
            String storageKey = BlobKeys.newKey(tenantId, hash);
            publish(tenantId, storageKey, temporary, written, contentType);
            return new StoredBlob(storageKey, backendName(), written, hash);
        }
        catch (IOException | NoSuchAlgorithmException ex) {
            throw new BlobStorageException("Could not store the object", ex);
        }
        finally {
            deleteQuietly(temporary);
        }
    }

    /** Directory for spooled uploads; must be writable and outside the published namespace. */
    protected abstract Path temporaryDirectory();

    /** Moves the spooled file to its final location. The implementation takes ownership. */
    protected abstract void publish(UUID tenantId, String storageKey, Path spooled, long sizeBytes,
                                    String contentType);

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        }
        catch (IOException ignored) {
            // A leftover spool file is harmless; the storage root is not the published namespace.
        }
    }
}
