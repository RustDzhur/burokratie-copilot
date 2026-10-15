package de.buerokratiecopilot.shared.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.SecretKey;

/**
 * Encrypts what goes into any storage backend with the key of the workspace the document belongs to (S05-03).
 *
 * <p>The record of the document keeps the size and the SHA-256 of the plaintext, so duplicate detection and the checks that
 * compare hashes work as before; the object in the backend holds only the sealed bytes. An object stored before encryption was
 * switched on does not start with the marker of the format and is read as it is, so nothing has to be converted first.</p>
 */
public class EncryptingBlobStore implements BlobStore {

    private final BlobStore delegate;
    private final TenantKeyService keys;

    public EncryptingBlobStore(BlobStore delegate, TenantKeyService keys) {
        this.delegate = delegate;
        this.keys = keys;
    }

    @Override
    public StoredBlob store(UUID tenantId, InputStream data, long sizeBytes, String contentType) {
        SecretKey key = keys.keyOf(tenantId);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is part of every Java platform", ex);
        }
        long[] counted = new long[1];
        InputStream counting = new java.io.FilterInputStream(new DigestInputStream(data, digest)) {
            @Override
            public int read() throws IOException {
                int b = super.read();
                if (b >= 0) {
                    counted[0]++;
                }
                return b;
            }

            @Override
            public int read(byte[] target, int offset, int length) throws IOException {
                int n = super.read(target, offset, length);
                if (n > 0) {
                    counted[0] += n;
                }
                return n;
            }
        };
        StoredBlob stored = delegate.store(tenantId, ChunkCipher.seal(counting, key, keys.version()),
                sizeBytes < 0 ? -1 : ChunkCipher.sealedSize(sizeBytes), contentType);
        if (sizeBytes >= 0 && counted[0] != sizeBytes) {
            delegate.delete(stored.storageKey());
            throw new BlobStorageException("Uploaded size %d does not match the declared size %d".formatted(counted[0], sizeBytes));
        }
        return new StoredBlob(stored.storageKey(), stored.backend(), counted[0], HexFormat.of().formatHex(digest.digest()));
    }

    @Override
    public InputStream retrieve(String storageKey) {
        InputStream raw = delegate.retrieve(storageKey);
        try {
            PushbackInputStream pushback = new PushbackInputStream(raw, ChunkCipher.MAGIC.length);
            byte[] head = new byte[ChunkCipher.MAGIC.length];
            int n = pushback.readNBytes(head, 0, head.length);
            if (!ChunkCipher.isSealed(head, n)) {
                pushback.unread(head, 0, n);
                return pushback; // stored before encryption was switched on
            }
            pushback.unread(head, 0, n);
            return ChunkCipher.open(pushback, keys.keyOf(BlobKeys.tenantOf(storageKey)));
        }
        catch (IOException ex) {
            try {
                raw.close();
            }
            catch (IOException ignored) {
                // nothing more to do for a stream that failed to open
            }
            throw new BlobStorageException("The stored object could not be opened", ex);
        }
    }

    @Override
    public void delete(String storageKey) {
        delegate.delete(storageKey);
    }

    @Override
    public boolean exists(String storageKey) {
        return delegate.exists(storageKey);
    }

    @Override
    public String backendName() {
        return delegate.backendName();
    }

    @Override
    public boolean isAvailable() {
        return delegate.isAvailable();
    }
}
