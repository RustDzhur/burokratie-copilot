package de.buerokratiecopilot.cases.domain;

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
 * A case: the thread that holds a matter together (spec §11.1).
 *
 * <p>A case is what turns a pile of documents into work: it says which matter they belong to, who is
 * responsible, what has to happen by when, and how it ended. Everything the platform knows about a
 * matter hangs off this record, which is why the reference, the responsible person and the outcome are
 * first-class fields rather than free text.</p>
 */
@Entity
@Table(name = "cases")
public class Case {

    /** States a case can be in; the transitions are checked by the lifecycle (task C-002). */
    public enum Status {
        NEW, IN_PROGRESS, WAITING_FOR_USER, WAITING_FOR_EXTERNAL_PARTY, WAITING_FOR_DOCUMENTS,
        WAITING_FOR_APPROVAL, ON_HOLD, COMPLETED, CLOSED, REOPENED
    }

    public enum Priority {
        CRITICAL, HIGH, NORMAL, LOW, INFORMATIONAL
    }

    public enum Confidentiality {
        PUBLIC, INTERNAL, CONFIDENTIAL, STRICT
    }

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "workspace_id")
    private UUID workspaceId;

    @Column(name = "organization_id")
    private UUID organizationId;

    /** Human readable case number; unique per tenant so it can be quoted in correspondence. */
    @Column(nullable = false, length = 60)
    private String reference;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(name = "case_type_id")
    private UUID caseTypeId;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private Status status = Status.NEW;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Priority priority = Priority.NORMAL;

    @Column(name = "owner_user_id")
    private UUID ownerUserId;

    @Column(name = "responsible_user_id")
    private UUID responsibleUserId;

    /** The party on the other side, which for a company is usually a supplier or a customer. */
    @Column(name = "counterparty_contact_id")
    private UUID counterpartyContactId;

    /** The file number used by the authority, quoted in every letter about this matter. */
    @Column(name = "external_reference", length = 120)
    private String externalReference;

    @Column(name = "authority_contact_id")
    private UUID authorityContactId;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt = Instant.now();

    @Column(name = "due_at")
    private Instant dueAt;

    /** When the case started waiting for somebody else, which is what ages a case. */
    @Column(name = "waiting_since")
    private Instant waitingSince;

    private String outcome;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "closed_by")
    private UUID closedBy;

    @Column(name = "close_reason", length = 60)
    private String closeReason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Confidentiality confidentiality = Confidentiality.INTERNAL;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private java.util.List<String> tags = java.util.List.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> metadata = Map.of();

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Case() {
        // for JPA
    }

    public Case(UUID id, UUID tenantId, String reference, String title) {
        this.id = id;
        this.tenantId = tenantId;
        this.reference = reference;
        this.title = title;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(UUID workspaceId) {
        this.workspaceId = workspaceId;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(UUID organizationId) {
        this.organizationId = organizationId;
    }

    public String getReference() {
        return reference;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public UUID getCaseTypeId() {
        return caseTypeId;
    }

    public void setCaseTypeId(UUID caseTypeId) {
        this.caseTypeId = caseTypeId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Priority getPriority() {
        return priority;
    }

    public void setPriority(Priority priority) {
        this.priority = priority;
    }

    public UUID getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(UUID ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public UUID getResponsibleUserId() {
        return responsibleUserId;
    }

    public void setResponsibleUserId(UUID responsibleUserId) {
        this.responsibleUserId = responsibleUserId;
    }

    public UUID getCounterpartyContactId() {
        return counterpartyContactId;
    }

    public void setCounterpartyContactId(UUID counterpartyContactId) {
        this.counterpartyContactId = counterpartyContactId;
    }

    public String getExternalReference() {
        return externalReference;
    }

    public void setExternalReference(String externalReference) {
        this.externalReference = externalReference;
    }

    public UUID getAuthorityContactId() {
        return authorityContactId;
    }

    public void setAuthorityContactId(UUID authorityContactId) {
        this.authorityContactId = authorityContactId;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public void setDueAt(Instant dueAt) {
        this.dueAt = dueAt;
    }

    public Instant getWaitingSince() {
        return waitingSince;
    }

    public void setWaitingSince(Instant waitingSince) {
        this.waitingSince = waitingSince;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(Instant closedAt) {
        this.closedAt = closedAt;
    }

    public UUID getClosedBy() {
        return closedBy;
    }

    public void setClosedBy(UUID closedBy) {
        this.closedBy = closedBy;
    }

    public String getCloseReason() {
        return closeReason;
    }

    public void setCloseReason(String closeReason) {
        this.closeReason = closeReason;
    }

    public Confidentiality getConfidentiality() {
        return confidentiality;
    }

    public void setConfidentiality(Confidentiality confidentiality) {
        this.confidentiality = confidentiality;
    }

    public java.util.List<String> getTags() {
        return tags;
    }

    public void setTags(java.util.List<String> tags) {
        this.tags = tags == null ? java.util.List.of() : tags;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
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

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public boolean isOpen() {
        return status != Status.CLOSED && status != Status.COMPLETED;
    }
}
