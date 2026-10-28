package de.buerokratiecopilot.shared.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Filesystem backed object storage for development and offline deployments.
 *
 * <p>Content is spooled and verified by {@link StreamingBlobStore}; this class only decides where
 * the object finally lives. The resolved path is always compared with the storage root, which
 * defeats {@code ..} traversal even if a key bypassed {@link BlobKeys} (spec §21.2).</p>
 */
public class LocalFsBlobStore extends StreamingBlobStore {

    private static final Logger log = LoggerFactory.getLogger(LocalFsBlobStore.class);

    private final Path root;

    public LocalFsBlobStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        }
        catch (IOException ex) {
            throw new IllegalStateException("Cannot create the blob storage directory " + this.root, ex);
        }
    }

    @Override
    protected Path temporaryDirectory() {
        // Outside the published namespace: a key can never collide with a spooled upload.
        return root.resolve(".tmp");
    }

    @Override
    protected void publish(UUID tenantId, String storageKey, Path spooled, long sizeBytes, String contentType) {
        Path target = resolve(storageKey);
        try {
            Files.createDirectories(target.getParent());
            try {
                Files.move(spooled, target, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (IOException atomicMoveUnsupported) {
                // Some container mounts cannot move atomically across directories; a plain replace
                // is still safe because the target has not been published yet.
                Files.move(spooled, target, StandardCopyOption.REPLACE_EXISTING);
            }
            log.debug("Stored blob {} ({} bytes) for tenant {}", storageKey, sizeBytes, tenantId);
        }
        catch (IOException ex) {
            throw new BlobStorageException("Could not publish the stored object", ex);
        }
    }

    @Override
    public InputStream retrieve(String storageKey) {
        Path path = resolve(storageKey);
        try {
            if (!Files.isRegularFile(path)) {
                throw new BlobStorageException("Object not found");
            }
            return Files.newInputStream(path);
        }
        catch (IOException ex) {
            throw new BlobStorageException("Could not read the object", ex);
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(resolve(storageKey));
        }
        catch (IOException ex) {
            throw new BlobStorageException("Could not delete the object", ex);
        }
    }

    @Override
    public boolean exists(String storageKey) {
        return Files.isRegularFile(resolve(storageKey));
    }

    @Override
    public String backendName() {
        return "local-fs";
    }

    @Override
    public boolean isAvailable() {
        return Files.isDirectory(root) && Files.isWritable(root);
    }

    /** Resolves a key inside the storage root and refuses anything that escapes it. */
    private Path resolve(String storageKey) {
        if (!BlobKeys.isValid(storageKey)) {
            throw new BlobAccessException("Malformed storage key");
        }
        Path candidate = root.resolve(storageKey).normalize();
        if (!candidate.startsWith(root)) {
            throw new BlobAccessException("Storage key escapes the storage root");
        }
        return candidate;
    }
}
