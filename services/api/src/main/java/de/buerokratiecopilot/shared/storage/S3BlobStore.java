package de.buerokratiecopilot.shared.storage;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.UUID;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;

/**
 * S3-compatible object storage (MinIO in development, any S3 provider in production).
 *
 * <p>The client is configured with an explicit endpoint, region and path-style access, so the same
 * code works against MinIO without TLS and against a cloud provider with virtual-host style
 * addressing. Credentials come from the environment and are never logged (spec §1.2, §21.1).</p>
 */
public class S3BlobStore extends StreamingBlobStore {

    private final MinioClient client;
    private final String bucket;
    private final Path spoolDirectory;

    public S3BlobStore(MinioClient client, String bucket, Path spoolDirectory) {
        this.client = client;
        this.bucket = bucket;
        this.spoolDirectory = spoolDirectory;
    }

    @Override
    protected Path temporaryDirectory() {
        return spoolDirectory;
    }

    @Override
    protected void publish(UUID tenantId, String storageKey, Path spooled, long sizeBytes, String contentType) {
        try {
            ensureBucket();
            try (InputStream in = java.nio.file.Files.newInputStream(spooled)) {
                client.putObject(PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(storageKey)
                        .stream(in, sizeBytes, -1)
                        .contentType(contentType == null ? "application/octet-stream" : contentType)
                        // The content hash is part of the key, so the object is immutable and can be
                        // cached aggressively.
                        .headers(java.util.Map.of("X-Amz-Meta-Tenant-Id", tenantId.toString()))
                        .build());
            }
        }
        catch (Exception ex) {
            throw new BlobStorageException("Could not upload the object to S3 storage", ex);
        }
    }

    @Override
    public InputStream retrieve(String storageKey) {
        requireValidKey(storageKey);
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(storageKey).build());
        }
        catch (Exception ex) {
            throw new BlobStorageException("Could not read the object from S3 storage", ex);
        }
    }

    @Override
    public void delete(String storageKey) {
        requireValidKey(storageKey);
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(storageKey).build());
        }
        catch (Exception ex) {
            throw new BlobStorageException("Could not delete the object from S3 storage", ex);
        }
    }

    @Override
    public boolean exists(String storageKey) {
        requireValidKey(storageKey);
        try {
            client.statObject(StatObjectArgs.builder().bucket(bucket).object(storageKey).build());
            return true;
        }
        catch (ErrorResponseException ex) {
            // "NoSuchKey" is a normal answer; anything else is a real failure.
            return false;
        }
        catch (Exception ex) {
            throw new BlobStorageException("Could not check the object in S3 storage", ex);
        }
    }

    @Override
    public String backendName() {
        return "s3";
    }

    @Override
    public boolean isAvailable() {
        try {
            client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            return true;
        }
        catch (Exception ex) {
            return false;
        }
    }

    /** Creates the bucket when missing so a fresh MinIO instance works without manual setup. */
    private void ensureBucket() throws Exception {
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    private static void requireValidKey(String storageKey) {
        if (!BlobKeys.isValid(storageKey)) {
            throw new BlobAccessException("Malformed storage key");
        }
    }

    /** Exposed for diagnostics: the object key is safe to log, the credentials never are. */
    String bucketName() {
        return bucket;
    }
}
