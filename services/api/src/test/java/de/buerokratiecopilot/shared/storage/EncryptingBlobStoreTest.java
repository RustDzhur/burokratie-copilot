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
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.buerokratiecopilot.shared.config.BurokratieProperties;

/** S05-03: documents are sealed with the key of their workspace; the record keeps the plaintext hash and size; tampering is detected. */
class EncryptingBlobStoreTest {

    /** Keys held in memory instead of the database. */
    static class MemoryKeys extends TenantKeyService {
        private final Map<UUID, SecretKey> keys = new ConcurrentHashMap<>();

        MemoryKeys() {
            super(null, properties(), null);
        }

        private static BurokratieProperties properties() {
            BurokratieProperties properties = new BurokratieProperties();
            properties.getSecurity().getEncryption().setKeyBase64(java.util.Base64.getEncoder().encodeToString(new byte[32]));
            return properties;
        }

        @Override
        public SecretKey keyOf(UUID tenantId) {
            return keys.computeIfAbsent(tenantId, id -> {
                byte[] raw = new byte[32];
                new SecureRandom().nextBytes(raw);
                return new SecretKeySpec(raw, "AES");
            });
        }
    }

    @TempDir
    Path root;

    private LocalFsBlobStore plain;
    private EncryptingBlobStore store;
    private final UUID tenant = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        plain = new LocalFsBlobStore(root.resolve("blobs"));
        store = new EncryptingBlobStore(plain, new MemoryKeys());
    }

    private static byte[] bytes(int length) {
        byte[] data = new byte[length];
        new SecureRandom().nextBytes(data);
        return data;
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    @Test
    @DisplayName("every size round-trips, including empty, one byte, around the chunk border and several chunks; hash and size are those of the plaintext")
    void roundTrips() throws Exception {
        for (int size : new int[] { 0, 1, ChunkCipher.CHUNK - 1, ChunkCipher.CHUNK, ChunkCipher.CHUNK + 1, 3 * ChunkCipher.CHUNK + 5 }) {
            byte[] content = bytes(size);
            for (long declared : new long[] { size, -1 }) {
                BlobStore.StoredBlob blob = store.store(tenant, new ByteArrayInputStream(content), declared, "application/pdf");
                assertThat(blob.sizeBytes()).as("size " + size).isEqualTo(size);
                assertThat(blob.contentHash()).isEqualTo(sha256(content));
                try (InputStream in = store.retrieve(blob.storageKey())) {
                    assertThat(in.readAllBytes()).as("content of " + size).isEqualTo(content);
                }
            }
        }
    }

    @Test
    @DisplayName("what lies in the backend is not the plaintext, and a text cannot be found in it")
    void backendHoldsCiphertext() throws Exception {
        byte[] content = "Rechnung 4711 IBAN DE89370400440532013000".getBytes(StandardCharsets.UTF_8);
        BlobStore.StoredBlob blob = store.store(tenant, new ByteArrayInputStream(content), content.length, "text/plain");

        byte[] onDisk;
        try (InputStream raw = plain.retrieve(blob.storageKey())) {
            onDisk = raw.readAllBytes();
        }
        assertThat(onDisk).startsWith(ChunkCipher.MAGIC);
        assertThat(new String(onDisk, StandardCharsets.ISO_8859_1)).doesNotContain("Rechnung").doesNotContain("DE8937");
        assertThat((long) onDisk.length).isEqualTo(ChunkCipher.sealedSize(content.length));
    }

    @Test
    @DisplayName("a changed byte, a file cut at a chunk border and a wrong declared size are all refused")
    void tamperingIsDetected() throws Exception {
        byte[] content = bytes(2 * ChunkCipher.CHUNK + 10);
        BlobStore.StoredBlob blob = store.store(tenant, new ByteArrayInputStream(content), content.length, "application/pdf");
        byte[] sealed;
        try (InputStream raw = plain.retrieve(blob.storageKey())) {
            sealed = raw.readAllBytes();
        }

        byte[] flipped = sealed.clone();
        flipped[flipped.length / 2] ^= 0x01;
        UUID flippedTenant = tenant;
        assertThatThrownBy(() -> read(ChunkCipher.open(new ByteArrayInputStream(flipped), keyOf(flippedTenant)))).isInstanceOf(IOException.class);

        int firstChunkEnd = 13 + ChunkCipher.CHUNK + ChunkCipher.TAG;
        byte[] cut = java.util.Arrays.copyOf(sealed, firstChunkEnd);
        assertThatThrownBy(() -> read(ChunkCipher.open(new ByteArrayInputStream(cut), keyOf(flippedTenant)))).isInstanceOf(IOException.class);

        assertThatThrownBy(() -> store.store(tenant, new ByteArrayInputStream(content), content.length + 1, "application/pdf"))
                .isInstanceOf(BlobStorageException.class);
    }

    private SecretKey keyOf(UUID id) throws Exception {
        // the key of the tenant as the store used it
        java.lang.reflect.Field keys = EncryptingBlobStore.class.getDeclaredField("keys");
        keys.setAccessible(true);
        return ((TenantKeyService) keys.get(store)).keyOf(id);
    }

    private static byte[] read(InputStream in) throws IOException {
        try (in) {
            return in.readAllBytes();
        }
    }

    @Test
    @DisplayName("another workspace's key does not open the document")
    void otherWorkspaceCannotRead() throws Exception {
        byte[] content = bytes(100);
        BlobStore.StoredBlob blob = store.store(tenant, new ByteArrayInputStream(content), content.length, "application/pdf");
        byte[] sealed;
        try (InputStream raw = plain.retrieve(blob.storageKey())) {
            sealed = raw.readAllBytes();
        }
        SecretKey other = keyOf(UUID.randomUUID());
        assertThatThrownBy(() -> read(ChunkCipher.open(new ByteArrayInputStream(sealed), other))).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("an object stored before encryption was switched on is read as it is")
    void legacyPlaintextIsRead() throws Exception {
        byte[] content = "alt und unverschlüsselt".getBytes(StandardCharsets.UTF_8);
        BlobStore.StoredBlob legacy = plain.store(tenant, new ByteArrayInputStream(content), content.length, "text/plain");
        try (InputStream in = store.retrieve(legacy.storageKey())) {
            assertThat(in.readAllBytes()).isEqualTo(content);
        }
        Path any = Files.walk(root).filter(Files::isRegularFile).findFirst().orElseThrow();
        assertThat(any).exists();
    }
}
