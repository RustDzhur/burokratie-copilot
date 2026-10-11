package de.buerokratiecopilot.documents.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One classification decision (spec §9.3).
 *
 * <p>History is kept rather than overwritten: when a user corrects a classification, the earlier
 * decision stays visible with its confidence and its rationale. That is what allows the quality of
 * the classifier to be judged later, and it is also what makes a manual correction auditable.</p>
 */
@Entity
@Table(name = "document_classifications")
public class DocumentClassification {

    @Id
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "version_id")
    private UUID versionId;

    @Column(name = "document_type_id", nullable = false)
    private UUID documentTypeId;

    @Column(precision = 5, scale = 4)
    private java.math.BigDecimal confidence;

    @Column(nullable = false, length = 20)
    private String method;

    private String rationale;

    @Column(name = "is_current", nullable = false)
    private boolean current = true;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected DocumentClassification() {
        // for JPA
    }

    public DocumentClassification(UUID id, UUID documentId, UUID versionId, UUID documentTypeId,
                                  String method) {
        this.id = id;
        this.documentId = documentId;
        this.versionId = versionId;
        this.documentTypeId = documentTypeId;
        this.method = method;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public UUID getVersionId() {
        return versionId;
    }

    public UUID getDocumentTypeId() {
        return documentTypeId;
    }

    public java.math.BigDecimal getConfidence() {
        return confidence;
    }

    public void setConfidence(java.math.BigDecimal confidence) {
        this.confidence = confidence;
    }

    public String getMethod() {
        return method;
    }

    public String getRationale() {
        return rationale;
    }

    public void setRationale(String rationale) {
        this.rationale = rationale;
    }

    public boolean isCurrent() {
        return current;
    }

    public void setCurrent(boolean current) {
        this.current = current;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(UUID createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
