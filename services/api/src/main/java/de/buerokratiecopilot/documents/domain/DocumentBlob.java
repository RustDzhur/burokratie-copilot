package de.buerokratiecopilot.documents.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One stored binary in the object store (spec §6.2).
 *
 * <p>A blob starts quarantined and may only be processed once the security scan is clean
 * (spec §21.2). The same content stored by the same tenant shares nothing: the row is per document
 * version, which keeps deletion and retention verifiable.</p>
 */
@Entity
@Table(name = "document_blobs")
public class DocumentBlob {

    public enum ScanStatus {
        PENDING, CLEAN, INFECTED, FAILED, SKIPPED
    }

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;

    @Column(name = "storage_backend", nullable = false, length = 30)
    private String storageBackend;

    @Column(name = "bucket", length = 200)
    private String bucket;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "content_type", nullable = false, length = 150)
    private String contentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "scan_status", nullable = false, length = 30)
    private ScanStatus scanStatus = ScanStatus.PENDING;

    @Column(name = "quarantined", nullable = false)
    private boolean quarantined = true;

    /** Verdict and structural details of the security check; never contains file content. */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "scan_details", columnDefinition = "jsonb", nullable = false)
    private java.util.Map<String, Object> scanDetails = java.util.Map.of();

    @Column(name = "encryption", nullable = false, length = 30)
    private String encryption = "none";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected DocumentBlob() {
        // for JPA
    }

    public DocumentBlob(UUID id, UUID tenantId, String storageKey, String storageBackend,
                        long sizeBytes, String contentHash, String contentType) {
        this.id = id;
        this.tenantId = tenantId;
        this.storageKey = storageKey;
        this.storageBackend = storageBackend;
        this.sizeBytes = sizeBytes;
        this.contentHash = contentHash;
        this.contentType = contentType;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getStorageBackend() {
        return storageBackend;
    }

    public String getBucket() {
        return bucket;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getContentHash() {
        return contentHash;
    }

    public String getContentType() {
        return contentType;
    }

    public ScanStatus getScanStatus() {
        return scanStatus;
    }

    public boolean isQuarantined() {
        return quarantined;
    }

    public String getEncryption() {
        return encryption;
    }

    public java.util.Map<String, Object> getScanDetails() {
        return scanDetails;
    }

    public void setScanDetails(java.util.Map<String, Object> scanDetails) {
        this.scanDetails = scanDetails == null ? java.util.Map.of() : scanDetails;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    /** Releases the blob for processing after a successful security check. */
    public void markClean() {
        this.scanStatus = ScanStatus.CLEAN;
        this.quarantined = false;
    }

    public void markScanFailed(String reason) {
        this.scanStatus = ScanStatus.FAILED;
        this.quarantined = true;
    }

    /**
     * Records that no scanner is configured in this deployment.
     *
     * <p>Distinct from {@link #markClean()}: "not checked" must never be presented as "checked and
     * safe". The value is part of the blob metadata so an operator can see which files were never
     * scanned.</p>
     */
    public void markScanSkipped() {
        this.scanStatus = ScanStatus.SKIPPED;
        this.quarantined = false;
    }

    public void markInfected() {
        this.scanStatus = ScanStatus.INFECTED;
        this.quarantined = true;
    }

    /** Only content that passed the scan may be handed to OCR or an AI provider. */
    public boolean isProcessable() {
        return scanStatus == ScanStatus.CLEAN || scanStatus == ScanStatus.SKIPPED;
    }
}
