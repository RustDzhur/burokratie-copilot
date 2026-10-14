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
 * Something that happened to a case (spec §11.1).
 *
 * <p>The timeline is the answer to "what is the state of this matter", which is the question a user
 * actually has. Keeping it as data rather than as a log file means the case card can show it, and the
 * specification requires every status change to carry its reason.</p>
 */
@Entity
@Table(name = "case_events")
public class CaseEvent {

    public enum EventType {
        CASE_CREATED, STATUS_CHANGED, DOCUMENT_LINKED, DOCUMENT_UNLINKED, TASK_CREATED, TASK_COMPLETED,
        DEADLINE_SET, DEADLINE_CHANGED, NOTE_ADDED, CORRESPONDENCE_SENT, CORRESPONDENCE_RECEIVED,
        PAYMENT_RECORDED, CONTRACT_LINKED, ASSIGNEE_CHANGED, CASE_CLOSED, CASE_REOPENED,
        APPROVAL_REQUESTED, APPROVAL_GRANTED, APPROVAL_REJECTED,
        GOAL_SET, CRITERION_ADDED, CRITERION_MET, CRITERION_WAIVED, PLAN_PROPOSED, PLAN_STARTED, REPLY_LINKED
    }

    @Id
    private UUID id;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private EventType eventType;

    /** Short headline of the entry, as it appears in the timeline. */
    @Column(nullable = false, length = 300)
    private String title;

    /** Longer explanation, for the entry a user opens. */
    private String description;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "document_id")
    private UUID documentId;

    @Column(name = "task_id")
    private UUID taskId;

    @Column(name = "contact_id")
    private UUID contactId;

    /** What changed, so the timeline can show more than a sentence when it needs to. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> metadata = Map.of();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected CaseEvent() {
        // for JPA
    }

    public CaseEvent(UUID id, UUID tenantId, UUID caseId, EventType eventType, UUID actorUserId,
                     String title) {
        this.id = id;
        this.tenantId = tenantId;
        this.caseId = caseId;
        this.eventType = eventType;
        this.actorUserId = actorUserId;
        this.title = title;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public EventType getEventType() {
        return eventType;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public void setDocumentId(UUID documentId) {
        this.documentId = documentId;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public void setTaskId(UUID taskId) {
        this.taskId = taskId;
    }

    public UUID getContactId() {
        return contactId;
    }

    public void setContactId(UUID contactId) {
        this.contactId = contactId;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata == null ? Map.of() : metadata;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
