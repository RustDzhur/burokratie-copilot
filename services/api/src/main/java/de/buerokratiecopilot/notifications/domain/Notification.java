package de.buerokratiecopilot.notifications.domain;

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
 * Something a user should know about (spec §25).
 *
 * <p>The text is stored as a key plus parameters, not as a finished sentence: the same notification is
 * read in three languages by three people, and a translated notification must not require the original
 * to be rewritten. It also keeps personal data out of the stored text — the parameters name the entity,
 * and the wording is chosen when it is shown.</p>
 *
 * <p>{@code isConfidential} is the flag the channels honour: a confidential notification may appear in
 * a push message only as "you have a new notification".</p>
 */
@Entity
@Table(name = "notifications")
public class Notification {

    public enum Severity {
        INFO, NOTICE, WARNING, CRITICAL
    }

    /** The categories of the specification; the schema fixes the values. */
    public enum Category {
        TASK, DEADLINE, MISSED_DEADLINE, NEW_MAIL, DOCUMENT_REVIEW, APPROVAL, INTEGRATION_ERROR,
        AUTOMATION_RESULT, EXTERNAL_RESPONSE, CONTRACT_CHANGE, ARCHIVING, SECURITY, SYSTEM, RADAR
    }

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "notification_type", nullable = false, length = 60)
    private String notificationType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Severity severity = Severity.INFO;

    /** Message key of the heading, resolved to a sentence when it is shown. */
    @Column(name = "title_key", nullable = false, length = 120)
    private String titleKey;

    @Column(name = "body_key", length = 120)
    private String bodyKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> parameters = Map.of();

    @Column(name = "entity_type", length = 60)
    private String entityType;

    @Column(name = "entity_id")
    private UUID entityId;

    @Column(name = "action_url", length = 300)
    private String actionUrl;

    @Column(name = "is_read", nullable = false)
    private boolean read;

    @Column(name = "read_at")
    private Instant readAt;

    /**
     * True when the details must not leave the platform on a channel a stranger could see.
     * The default is false only because a caller must say so; anything touching a person's affairs
     * should set it.
     */
    @Column(name = "is_confidential", nullable = false)
    private boolean confidential;

    /** Stops the same event producing the same notification twice. */
    @Column(name = "dedup_key", length = 200)
    private String dedupKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at")
    private Instant expiresAt;

    protected Notification() {
        // for JPA
    }

    public Notification(UUID id, UUID tenantId, UUID userId, String notificationType, Category category,
                        String titleKey) {
        this.id = id;
        this.tenantId = tenantId;
        this.userId = userId;
        this.notificationType = notificationType;
        this.category = category;
        this.titleKey = titleKey;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getNotificationType() {
        return notificationType;
    }

    public Category getCategory() {
        return category;
    }

    public Severity getSeverity() {
        return severity;
    }

    public void setSeverity(Severity severity) {
        this.severity = severity;
    }

    public String getTitleKey() {
        return titleKey;
    }

    public String getBodyKey() {
        return bodyKey;
    }

    public void setBodyKey(String bodyKey) {
        this.bodyKey = bodyKey;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, Object> parameters) {
        this.parameters = parameters == null ? Map.of() : parameters;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public UUID getEntityId() {
        return entityId;
    }

    public void setEntityId(UUID entityId) {
        this.entityId = entityId;
    }

    public String getActionUrl() {
        return actionUrl;
    }

    public void setActionUrl(String actionUrl) {
        this.actionUrl = actionUrl;
    }

    public boolean isRead() {
        return read;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public boolean isConfidential() {
        return confidential;
    }

    public void setConfidential(boolean confidential) {
        this.confidential = confidential;
    }

    public String getDedupKey() {
        return dedupKey;
    }

    public void setDedupKey(String dedupKey) {
        this.dedupKey = dedupKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    /** Marking read twice keeps the first moment: when a person saw it is what matters. */
    public void markRead() {
        if (!read) {
            this.read = true;
            this.readAt = Instant.now();
        }
    }

    /** Marking unread clears the moment, because the schema ties the flag and the timestamp. */
    public void markUnread() {
        this.read = false;
        this.readAt = null;
    }
}
