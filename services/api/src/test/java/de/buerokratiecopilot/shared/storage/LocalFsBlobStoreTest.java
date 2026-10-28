package de.buerokratiecopilot.shared.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the filesystem backed object store, which is the backend used in development and in
 * offline deployments. The S3 implementation shares the same streaming base class and the same key
 * validation; it requires a running S3-compatible service and is therefore exercised in the
 * deployment environment, not here (documented in docs/implementation-plan.md, Q-01/Q-06).
 */
class LocalFsBlobStoreTest {

    @TempDir
    Path root;

    private LocalFsBlobStore store;
    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void setUp() {
        store = new LocalFsBlobStore(root.resolve("blobs"));
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
    }

    private static byte[] bytes(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("content is stored with the correct hash, read back and deleted")
    void storeRetrieveDelete() throws Exception {
        byte[] content = bytes("Rechnung 2026-001: 119,00 EUR");

        BlobStore.StoredBlob stored = store.store(tenantA, new ByteArrayInputStream(content),
                content.length, "application/pdf");

        assertThat(stored.backend()).isEqualTo("local-fs");
        assertThat(stored.sizeBytes()).isEqualTo(content.length);
        assertThat(stored.contentHash()).isEqualTo(sha256(content));
        assertThat(store.exists(stored.storageKey())).isTrue();
        assertThat(store.isAvailable()).isTrue();

        try (InputStream in = store.retrieve(stored.storageKey())) {
            assertThat(in.readAllBytes()).isEqualTo(content);
        }

        store.delete(stored.storageKey());
        assertThat(store.exists(stored.storageKey())).isFalse();
        // Deleting twice is not an error: the operation is idempotent.
        store.delete(stored.storageKey());
    }

    @Test
    @DisplayName("the key contains the tenant and never the original file name")
    void keyCarriesTenantAndNoFileName() {
        BlobStore.StoredBlob stored = store.store(tenantA,
                new ByteArrayInputStream(bytes("x")), 1, "text/plain");

        assertThat(stored.storageKey()).startsWith("tenants/" + tenantA + "/");
        assertThat(stored.storageKey()).doesNotContain(".pdf").doesNotContain("..");
        assertThat(BlobKeys.tenantOf(stored.storageKey())).isEqualTo(tenantA);
    }

    @Test
    @DisplayName("an upload whose size does not match the declaration is rejected and leaves nothing behind")
    void truncatedUploadIsRejected() {
        byte[] content = bytes("vollständiger Inhalt");

        assertThatThrownBy(() -> store.store(tenantA, new ByteArrayInputStream(content),
                content.length + 100, "text/plain"))
                .isInstanceOf(BlobStorageException.class)
                .hasMessageContaining("does not match");

        assertThat(countPublishedFiles()).as("no half written object may be published").isZero();
    }

    @Test
    @DisplayName("a key of another tenant is refused even when it is well formed")
    void crossTenantKeyIsRefused() {
        BlobStore.StoredBlob stored = store.store(tenantB,
                new ByteArrayInputStream(bytes("secret")), 6, "text/plain");

        assertThatThrownBy(() -> BlobKeys.requireTenant(stored.storageKey(), tenantA))
                .isInstanceOf(BlobAccessException.class)
                .hasMessageContaining("does not belong");

        assertThat(BlobKeys.requireTenant(stored.storageKey(), tenantB)).isEqualTo(stored.storageKey());
    }

    @Test
    @DisplayName("path traversal, absolute paths and malformed keys are refused")
    void malformedKeysAreRefused() {
        assertThatThrownBy(() -> store.retrieve("tenants/" + tenantA + "/../../etc/passwd"))
                .isInstanceOf(BlobAccessException.class);
        assertThatThrownBy(() -> store.retrieve("/etc/passwd"))
                .isInstanceOf(BlobAccessException.class);
        assertThatThrownBy(() -> store.retrieve("tenants/" + tenantA + "/2026/10/ab/../../../secret"))
                .isInstanceOf(BlobAccessException.class);
        assertThatThrownBy(() -> store.retrieve(null))
                .isInstanceOf(BlobAccessException.class);
        assertThatThrownBy(() -> store.exists("random-key"))
                .isInstanceOf(BlobAccessException.class);

        assertThat(BlobKeys.isValid("tenants/" + tenantA + "/2026/10/ab/" + UUID.randomUUID())).isTrue();
        assertThat(BlobKeys.isValid("../../etc/passwd")).isFalse();
        assertThat(BlobKeys.isValid("tenants/not-a-uuid/2026/10/ab/" + UUID.randomUUID())).isFalse();
    }

    @Test
    @DisplayName("identical content stored twice produces two keys but the same hash")
    void identicalContentHasStableHash() {
        byte[] content = bytes("identisch");

        BlobStore.StoredBlob first = store.store(tenantA, new ByteArrayInputStream(content), content.length, "text/plain");
        BlobStore.StoredBlob second = store.store(tenantA, new ByteArrayInputStream(content), content.length, "text/plain");

        assertThat(first.contentHash()).isEqualTo(second.contentHash());
        // The random suffix keeps the keys distinct so one document can never overwrite another.
        assertThat(first.storageKey()).isNotEqualTo(second.storageKey());
    }

    @Test
    @DisplayName("a large upload is streamed, not buffered: memory use stays bounded")
    void largeUploadIsStreamed() {
        long size = 24L * 1024 * 1024;
        InputStream zeros = new java.io.InputStream() {
            private long remaining = size;

            @Override
            public int read() {
                if (remaining == 0) {
                    return -1;
                }
                remaining--;
                return 0;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                if (remaining == 0) {
                    return -1;
                }
                int chunk = (int) Math.min(length, remaining);
                java.util.Arrays.fill(buffer, offset, offset + chunk, (byte) 0);
                remaining -= chunk;
                return chunk;
            }
        };

        long before = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        BlobStore.StoredBlob stored = store.store(tenantA, zeros, size, "application/octet-stream");
        long after = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

        assertThat(stored.sizeBytes()).isEqualTo(size);
        assertThat(after - before)
                .as("a 24 MiB upload must not be buffered in memory")
                .isLessThan(8L * 1024 * 1024);
    }

    private long countPublishedFiles() {
        Path blobs = root.resolve("blobs");
        if (!Files.isDirectory(blobs)) {
            return 0;
        }
        try (var stream = Files.walk(blobs)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> !path.startsWith(blobs.resolve(".tmp")))
                    .count();
        }
        catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
