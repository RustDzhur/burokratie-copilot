package de.buerokratiecopilot.notifications.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import de.buerokratiecopilot.notifications.NotificationChannelAdapter.Channel;

/**
 * What happened to one notification on one channel (spec §25).
 *
 * <p>The record exists so "did the user get it" has an answer that is not a guess. It is also where
 * the two refusals become visible: {@code SUPPRESSED} when the user switched the channel off, and
 * {@code SKIPPED} when the deployment cannot send — different facts, and only one of them is the
 * user's choice.</p>
 */
@Entity
@Table(name = "notification_deliveries")
public class NotificationDelivery {

    public enum Status {
        PENDING, SENT, FAILED, SKIPPED, SUPPRESSED
    }

    @Id
    private UUID id;

    @Column(name = "notification_id", nullable = false)
    private UUID notificationId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Channel channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "provider_message_id", length = 200)
    private String providerMessageId;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected NotificationDelivery() {
        // for JPA
    }

    public NotificationDelivery(UUID id, UUID tenantId, UUID notificationId, Channel channel) {
        this.id = id;
        this.tenantId = tenantId;
        this.notificationId = notificationId;
        this.channel = channel;
    }

    public UUID getId() {
        return id;
    }

    public UUID getNotificationId() {
        return notificationId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public Channel getChannel() {
        return channel;
    }

    public Status getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getProviderMessageId() {
        return providerMessageId;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void markSent(String providerMessageId) {
        this.status = Status.SENT;
        this.attempts++;
        this.sentAt = Instant.now();
        this.providerMessageId = providerMessageId;
        this.errorMessage = null;
    }

    public void markFailed(String error) {
        this.status = Status.FAILED;
        this.attempts++;
        this.errorMessage = error;
    }

    /** The user switched this channel off for this category: their decision, not a failure. */
    public void markSuppressed(String reason) {
        this.status = Status.SUPPRESSED;
        this.errorMessage = reason;
    }

    /** The deployment cannot send on this channel: nobody decided anything. */
    public void markSkipped(String reason) {
        this.status = Status.SKIPPED;
        this.errorMessage = reason;
    }
}
