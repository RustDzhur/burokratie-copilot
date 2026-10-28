package de.buerokratiecopilot.documents.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;

/**
 * An immutable version of a document (spec §6.2).
 *
 * <p>Replacing a file never overwrites anything: a new version is appended, which is what makes the
 * history, the content hash check and the archiving requirements verifiable.</p>
 */
@Entity
@Table(name = "document_versions")
public class DocumentVersion {

    @Id
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private int version;

    @Column(name = "blob_id", nullable = false)
    private UUID blobId;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "mime_type", nullable = false, length = 150)
    private String mimeType;

    @Column(name = "original_filename", nullable = false, length = 400)
    private String originalFilename;

    @Column(name = "change_note", length = 500)
    private String changeNote;

    @Column(name = "created_by")
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DocumentVersion() {
        // for JPA
    }

    public DocumentVersion(UUID id, UUID documentId, UUID tenantId, int version, UUID blobId,
                           String contentHash, long sizeBytes, String mimeType, String originalFilename) {
        this.id = id;
        this.documentId = documentId;
        this.tenantId = tenantId;
        this.version = version;
        this.blobId = blobId;
        this.contentHash = contentHash;
        this.sizeBytes = sizeBytes;
        this.mimeType = mimeType;
        this.originalFilename = originalFilename;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public int getVersion() {
        return version;
    }

    public UUID getBlobId() {
        return blobId;
    }

    public String getContentHash() {
        return contentHash;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getMimeType() {
        return mimeType;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getChangeNote() {
        return changeNote;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
