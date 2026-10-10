package de.buerokratiecopilot.shared.storage;

import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.minio.MinioClient;

import de.buerokratiecopilot.shared.config.BurokratieProperties;

/**
 * Selects the object storage backend from configuration.
 *
 * <p>{@code local-fs} is the default so the platform runs without any external service;
 * {@code s3} requires an endpoint and credentials and fails loudly when they are missing instead of
 * silently falling back to a different backend — a document that appears to be stored while it is
 * not would be worse than a startup error.</p>
 */
@Configuration(proxyBeanMethods = false)
public class StorageConfiguration {

    private static final Logger log = LoggerFactory.getLogger(StorageConfiguration.class);

    @Bean
    public BlobStore blobStore(BurokratieProperties properties) {
        BurokratieProperties.Storage storage = properties.getStorage();
        if ("s3".equalsIgnoreCase(storage.getType())) {
            BurokratieProperties.Storage.S3 s3 = storage.getS3();
            if (s3.getEndpoint() == null || s3.getEndpoint().isBlank()
                    || s3.getAccessKey() == null || s3.getAccessKey().isBlank()
                    || s3.getSecretKey() == null || s3.getSecretKey().isBlank()) {
                throw new IllegalStateException(
                        "burokratie.storage.type=s3 requires BC_S3_ENDPOINT, BC_S3_ACCESS_KEY and "
                        + "BC_S3_SECRET_KEY; refusing to start with an unusable storage backend");
            }
            MinioClient client = MinioClient.builder()
                    .endpoint(s3.getEndpoint())
                    .region(s3.getRegion())
                    .credentials(s3.getAccessKey(), s3.getSecretKey())
                    .build();
            // Path-style access is required by MinIO and by most S3-compatible services.
            client.setAppInfo("buerokratie-copilot", "1.0");
            log.info("Object storage: S3-compatible backend at {} (bucket {})", s3.getEndpoint(), s3.getBucket());
            return new S3BlobStore(client, s3.getBucket(),
                    Path.of(storage.getLocal().getBasePath()).resolve(".spool"));
        }

        log.info("Object storage: local filesystem at {}", storage.getLocal().getBasePath());
        return new LocalFsBlobStore(Path.of(storage.getLocal().getBasePath()));
    }
}
