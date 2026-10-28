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

import de.buerokratiecopilot.notifications.NotificationChannelAdapter.Channel;

/**
 * What a user wants to be told, where and how often (spec §25).
 *
 * <p>Frequency is part of the preference rather than a global setting: a deadline reminder is useful
 * immediately, a summary of finished automation is not. {@code NEVER} exists so a user can switch a
 * category off completely, which is a decision the platform must accept rather than route around.</p>
 */
@Entity
@Table(name = "notification_preferences")
public class NotificationPreference {

    public enum Frequency {
        IMMEDIATE, DAILY_DIGEST, WEEKLY_DIGEST, NEVER
    }

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Notification.Category category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Channel channel;

    @Column(name = "is_enabled", nullable = false)
    private boolean enabled = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Frequency frequency = Frequency.IMMEDIATE;

    /**
     * Hours in which nothing is sent; the notification is stored and waits. Required by the schema and
     * therefore empty rather than absent when the user has none - "no quiet hours" is a setting, not a
     * missing value.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "quiet_hours", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> quietHours = Map.of();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected NotificationPreference() {
        // for JPA
    }

    public NotificationPreference(UUID id, UUID tenantId, UUID userId, Notification.Category category,
                                  Channel channel) {
        this.id = id;
        this.tenantId = tenantId;
        this.userId = userId;
        this.category = category;
        this.channel = channel;
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

    public Notification.Category getCategory() {
        return category;
    }

    public Channel getChannel() {
        return channel;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        this.updatedAt = Instant.now();
    }

    public Frequency getFrequency() {
        return frequency;
    }

    public void setFrequency(Frequency frequency) {
        this.frequency = frequency;
        this.updatedAt = Instant.now();
    }

    public Map<String, Object> getQuietHours() {
        return quietHours;
    }

    public void setQuietHours(Map<String, Object> quietHours) {
        this.quietHours = quietHours;
        this.updatedAt = Instant.now();
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** Whether a notification of this category and channel may be sent right now. */
    public boolean allowsDelivery() {
        return enabled && frequency != Frequency.NEVER;
    }
}
