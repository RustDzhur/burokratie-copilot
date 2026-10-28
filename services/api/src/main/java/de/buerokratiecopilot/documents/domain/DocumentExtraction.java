package de.buerokratiecopilot.documents.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One extracted value together with where it came from (spec §9.4).
 *
 * <p>Provenance is not optional in this platform. Every value carries the page and the snippet it was
 * read from, so a reviewer can verify it against the original document rather than trusting the
 * extraction. A value without a source is unusable for an administrative decision, and the
 * specification says so explicitly.</p>
 */
@Entity
@Table(name = "document_extractions")
public class DocumentExtraction {

    @Id
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "version_id")
    private UUID versionId;

    @Column(name = "field_key", nullable = false, length = 80)
    private String fieldKey;

    /** The value as it appears in the document. */
    @Column(name = "field_value")
    private String fieldValue;

    /** The same value in a comparable form: dates as ISO, amounts as a decimal. */
    @Column(name = "normalized_value")
    private String normalizedValue;

    @Column(name = "value_type", nullable = false, length = 30)
    private String valueType;

    @Column(precision = 5, scale = 4)
    private BigDecimal confidence;

    @Column(name = "source_kind", nullable = false, length = 20)
    private String sourceKind;

    @Column(name = "source_page")
    private Integer sourcePage;

    @Column(name = "source_snippet")
    private String sourceSnippet;

    @Column(nullable = false, length = 20)
    private String extractor;

    @Column(name = "verified_by")
    private UUID verifiedBy;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected DocumentExtraction() {
        // for JPA
    }

    public DocumentExtraction(UUID id, UUID documentId, UUID versionId, String fieldKey,
                              String valueType, String extractor, String sourceKind) {
        this.id = id;
        this.documentId = documentId;
        this.versionId = versionId;
        this.fieldKey = fieldKey;
        this.valueType = valueType;
        this.extractor = extractor;
        this.sourceKind = sourceKind;
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

    public String getFieldKey() {
        return fieldKey;
    }

    public String getFieldValue() {
        return fieldValue;
    }

    public void setFieldValue(String fieldValue) {
        this.fieldValue = fieldValue;
    }

    public String getNormalizedValue() {
        return normalizedValue;
    }

    public void setNormalizedValue(String normalizedValue) {
        this.normalizedValue = normalizedValue;
    }

    public String getValueType() {
        return valueType;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public void setConfidence(BigDecimal confidence) {
        this.confidence = confidence;
    }

    public String getSourceKind() {
        return sourceKind;
    }

    public Integer getSourcePage() {
        return sourcePage;
    }

    public void setSourcePage(Integer sourcePage) {
        this.sourcePage = sourcePage;
    }

    public String getSourceSnippet() {
        return sourceSnippet;
    }

    public void setSourceSnippet(String sourceSnippet) {
        this.sourceSnippet = sourceSnippet;
    }

    public String getExtractor() {
        return extractor;
    }

    public UUID getVerifiedBy() {
        return verifiedBy;
    }

    public void setVerifiedBy(UUID verifiedBy) {
        this.verifiedBy = verifiedBy;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public void setVerifiedAt(Instant verifiedAt) {
        this.verifiedAt = verifiedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isVerified() {
        return verifiedAt != null;
    }
}
