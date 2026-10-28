package de.buerokratiecopilot.shared.outbox;

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
 * A row of the transactional outbox.
 *
 * <p>Status transitions are owned by the dispatcher:
 * {@code PENDING → PROCESSING → PUBLISHED}, with {@code FAILED} as a retry stop and
 * {@code DEAD_LETTER} once the attempts are exhausted. {@code event_id} is the stable identity of
 * the business event and is what consumers use for deduplication.</p>
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    public enum Status {
        PENDING,
        PROCESSING,
        PUBLISHED,
        FAILED,
        DEAD_LETTER
    }

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "aggregate_type", nullable = false, length = 60)
    private String aggregateType;

    @Column(name = "aggregate_id")
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt = Instant.now();

    @Column(name = "locked_by", length = 120)
    private String lockedBy;

    @Column(name = "locked_at")
    private Instant lockedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected OutboxEvent() {
        // for JPA
    }

    public static OutboxEvent from(DomainEvent event) {
        OutboxEvent row = new OutboxEvent();
        row.id = UUID.randomUUID();
        row.eventId = UUID.randomUUID();
        row.tenantId = event.tenantId();
        row.aggregateType = event.aggregateType();
        row.aggregateId = event.aggregateId();
        row.eventType = event.eventType();
        row.payload = event.payload();
        row.status = Status.PENDING;
        row.availableAt = Instant.now();
        return row;
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public Status getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getAvailableAt() {
        return availableAt;
    }

    public String getLockedBy() {
        return lockedBy;
    }

    public Instant getLockedAt() {
        return lockedAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void markPublished(Instant when) {
        this.status = Status.PUBLISHED;
        this.publishedAt = when;
        this.lockedBy = null;
        this.lockedAt = null;
        this.errorMessage = null;
    }

    /**
     * Marks a failed attempt and schedules the next one.
     *
     * @param deadLetter when the attempt budget is exhausted; a dead letter is never retried
     *                   automatically and has to be inspected by an operator
     */
    public void markFailed(String error, Instant nextAttempt, boolean deadLetter) {
        this.attempts++;
        this.errorMessage = error == null ? null : truncate(error);
        this.status = deadLetter ? Status.DEAD_LETTER : Status.FAILED;
        this.availableAt = nextAttempt;
        this.lockedBy = null;
        this.lockedAt = null;
    }

    /**
     * Releases a lock that was left behind by a crashed worker so the event becomes eligible again.
     */
    public void releaseStaleLock(Instant now) {
        this.status = Status.PENDING;
        this.lockedBy = null;
        this.lockedAt = null;
        this.availableAt = now;
    }

    private static String truncate(String value) {
        return value.length() <= 2000 ? value : value.substring(0, 2000);
    }
}
