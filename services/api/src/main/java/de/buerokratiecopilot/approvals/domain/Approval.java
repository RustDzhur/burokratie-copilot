package de.buerokratiecopilot.approvals.domain;

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
 * A decision somebody has to make before something happens (spec §15).
 *
 * <p>An approval names what it is about, who may decide it and what they need to hold to do so. The
 * authorisation is part of the record rather than a convention: a platform that lets anybody click
 * "approve" has an approval button, not an approval.</p>
 *
 * <p>{@code automationLevel} is kept from the schema: it says how far this particular approval may be
 * automated, and the base configuration never lets a payment execute itself.</p>
 */
@Entity
@Table(name = "approvals")
public class Approval {

    public enum ApprovalType {
        INVOICE_APPROVAL, PAYMENT_APPROVAL, CONTRACT_TERMINATION, OUTBOUND_MESSAGE, DOCUMENT_DELETION,
        DATA_EXPORT, ROLE_CHANGE, WORKFLOW_PUBLISH
    }

    public enum Status {
        PENDING, APPROVED, REJECTED, CANCELLED, EXPIRED
    }

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "subject_type", nullable = false, length = 60)
    private String subjectType;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_type", nullable = false, length = 40)
    private ApprovalType approvalType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    /** How far this approval may be automated; 1 means a person decides everything. */
    @Column(name = "automation_level", nullable = false)
    private int automationLevel = 1;

    @Column(nullable = false, length = 300)
    private String title;

    private String justification;

    @Column(name = "requested_by")
    private UUID requestedBy;

    /** What the decider must hold; {@code null} means any member with the approval right. */
    @Column(name = "required_permission", length = 120)
    private String requiredPermission;

    @Column(name = "required_role", length = 80)
    private String requiredRole;

    @Column(name = "assigned_to")
    private UUID assignedTo;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_note")
    private String decisionNote;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> metadata = Map.of();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Approval() {
        // for JPA
    }

    public Approval(UUID id, UUID tenantId, String subjectType, UUID subjectId,
                    ApprovalType approvalType, String title) {
        this.id = id;
        this.tenantId = tenantId;
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.approvalType = approvalType;
        this.title = title;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getSubjectType() {
        return subjectType;
    }

    public UUID getSubjectId() {
        return subjectId;
    }

    public ApprovalType getApprovalType() {
        return approvalType;
    }

    public Status getStatus() {
        return status;
    }

    public int getAutomationLevel() {
        return automationLevel;
    }

    public void setAutomationLevel(int automationLevel) {
        this.automationLevel = automationLevel;
    }

    public String getTitle() {
        return title;
    }

    public String getJustification() {
        return justification;
    }

    public void setJustification(String justification) {
        this.justification = justification;
    }

    public UUID getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(UUID requestedBy) {
        this.requestedBy = requestedBy;
    }

    public String getRequiredPermission() {
        return requiredPermission;
    }

    public void setRequiredPermission(String requiredPermission) {
        this.requiredPermission = requiredPermission;
    }

    public String getRequiredRole() {
        return requiredRole;
    }

    public void setRequiredRole(String requiredRole) {
        this.requiredRole = requiredRole;
    }

    public UUID getAssignedTo() {
        return assignedTo;
    }

    public void setAssignedTo(UUID assignedTo) {
        this.assignedTo = assignedTo;
    }

    public UUID getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getDecisionNote() {
        return decisionNote;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void addMetadata(String key, Object value) {
        Map<String, Object> updated = new java.util.HashMap<>(metadata);
        updated.put(key, value);
        this.metadata = Map.copyOf(updated);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isPending() {
        return status == Status.PENDING;
    }

    public boolean isApproved() {
        return status == Status.APPROVED;
    }

    /**
     * Records a decision.
     *
     * @throws IllegalStateException when the approval was already decided: a second decision must not
     *                               silently overwrite the first, or the trail loses its meaning
     */
    public void decide(Status decision, UUID deciderId, String note) {
        if (!isPending()) {
            throw new IllegalStateException(
                    "Die Freigabe wurde bereits entschieden (" + status + ") und kann nicht erneut "
                            + "entschieden werden");
        }
        if (decision != Status.APPROVED && decision != Status.REJECTED) {
            throw new IllegalArgumentException("A decision is either approved or rejected");
        }
        this.status = decision;
        this.decidedBy = deciderId;
        this.decidedAt = Instant.now();
        this.decisionNote = note;
        this.updatedAt = Instant.now();
    }
}
