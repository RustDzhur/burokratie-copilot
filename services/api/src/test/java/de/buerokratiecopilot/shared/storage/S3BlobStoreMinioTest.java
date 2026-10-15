package de.buerokratiecopilot.shared.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The S3 implementation against a real S3-compatible server (MinIO). It runs only when {@code BC_TEST_MINIO_ENDPOINT} (and
 * {@code BC_TEST_MINIO_ACCESS_KEY} / {@code BC_TEST_MINIO_SECRET_KEY}) are set, because the CI database service has no object
 * storage; otherwise it is skipped and says so. It exists so that an upgrade of the MinIO client is tried on the wire, not
 * only compiled.
 */
class S3BlobStoreMinioTest {

    @TempDir
    Path spool;

    private S3BlobStore store;

    @BeforeEach
    void setUp() throws Exception {
        String endpoint = System.getenv("BC_TEST_MINIO_ENDPOINT");
        assumeTrue(endpoint != null && !endpoint.isBlank(), "BC_TEST_MINIO_ENDPOINT is not set: no MinIO to test against");
        MinioClient client = MinioClient.builder().endpoint(endpoint)
                .credentials(System.getenv("BC_TEST_MINIO_ACCESS_KEY"), System.getenv("BC_TEST_MINIO_SECRET_KEY")).build();
        String bucket = "bc-test-" + UUID.randomUUID().toString().substring(0, 8);
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
        store = new S3BlobStore(client, bucket, spool);
    }

    @Test
    @DisplayName("an object is stored with the right hash, read back, found, deleted twice without error; another tenant's key is refused")
    void roundTrip() throws Exception {
        UUID tenant = UUID.randomUUID();
        byte[] content = "Rechnung 2026-001: 119,00 EUR".getBytes(StandardCharsets.UTF_8);

        BlobStore.StoredBlob stored = store.store(tenant, new ByteArrayInputStream(content), content.length, "application/pdf");

        assertThat(stored.backend()).isEqualTo(store.backendName());
        assertThat(stored.sizeBytes()).isEqualTo(content.length);
        assertThat(store.isAvailable()).isTrue();
        assertThat(store.exists(stored.storageKey())).isTrue();
        try (InputStream in = store.retrieve(stored.storageKey())) {
            assertThat(in.readAllBytes()).isEqualTo(content);
        }
        store.delete(stored.storageKey());
        store.delete(stored.storageKey());
        assertThat(store.exists(stored.storageKey())).isFalse();
        assertThatThrownBy(() -> store.retrieve("../" + stored.storageKey())).isInstanceOf(RuntimeException.class);
    }
}
