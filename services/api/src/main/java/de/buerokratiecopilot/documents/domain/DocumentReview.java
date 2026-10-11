package de.buerokratiecopilot.documents.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A question the platform asks a person (spec §9.6, §13).
 *
 * <p>A review is not an error state: it is the normal path for everything the platform is not entitled
 * to decide alone — a type it is not sure about, a field it could not read, a link it will not create
 * silently. Storing the reason makes the queue usable: "33 documents waiting" is a number, "12 of them
 * because the type is unclear" is work.</p>
 */
@Entity
@Table(name = "document_reviews")
public class DocumentReview {

    /** What the question is about. The values are fixed by the schema. */
    public enum ReviewType {
        CLASSIFICATION, EXTRACTION, DUPLICATE, SECURITY, MATCHING, COMPLETENESS
    }

    public enum Status {
        PENDING, IN_REVIEW, APPROVED, REJECTED, CANCELLED
    }

    @Id
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "version_id")
    private UUID versionId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_type", nullable = false, length = 30)
    private ReviewType reviewType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    private String reason;

    @Column(name = "assigned_to")
    private UUID assignedTo;

    @Column(name = "requested_by")
    private UUID requestedBy;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decision_note")
    private String decisionNote;

    /** What the reviewer changed, so the correction can be learned from later. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> changes = Map.of();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "decided_at")
    private Instant decidedAt;

    protected DocumentReview() {
        // for JPA
    }

    public DocumentReview(UUID id, UUID tenantId, UUID documentId, UUID versionId, ReviewType reviewType,
                          String reason) {
        this.id = id;
        this.tenantId = tenantId;
        this.documentId = documentId;
        this.versionId = versionId;
        this.reviewType = reviewType;
        this.reason = reason;
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

    public UUID getTenantId() {
        return tenantId;
    }

    public ReviewType getReviewType() {
        return reviewType;
    }

    public Status getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public UUID getAssignedTo() {
        return assignedTo;
    }

    public void setAssignedTo(UUID assignedTo) {
        this.assignedTo = assignedTo;
    }

    public UUID getRequestedBy() {
        return requestedBy;
    }

    public UUID getDecidedBy() {
        return decidedBy;
    }

    public String getDecisionNote() {
        return decisionNote;
    }

    public Map<String, Object> getChanges() {
        return changes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public boolean isOpen() {
        return status == Status.PENDING || status == Status.IN_REVIEW;
    }

    /** Records the decision. Only a person can do this, and the platform records who. */
    public void decide(Status decision, UUID userId, String note, Map<String, Object> appliedChanges) {
        if (decision != Status.APPROVED && decision != Status.REJECTED && decision != Status.CANCELLED) {
            throw new IllegalArgumentException("A review can only be approved, rejected or cancelled");
        }
        this.status = decision;
        this.decidedBy = userId;
        this.decisionNote = note;
        this.changes = appliedChanges == null ? Map.of() : appliedChanges;
        this.decidedAt = Instant.now();
    }

    public void claim(UUID userId) {
        this.assignedTo = userId;
        this.status = Status.IN_REVIEW;
    }
}
