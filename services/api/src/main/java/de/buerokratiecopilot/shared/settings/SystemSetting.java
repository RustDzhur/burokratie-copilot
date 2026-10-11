package de.buerokratiecopilot.shared.settings;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A configurable value of the platform (spec §31).
 *
 * <p>Thresholds, limits and business rules live here rather than in code, because the specification
 * requires them to be changeable and versioned. {@code requiresVerification} marks the ones that
 * change legal or financial behaviour: those must be confirmed by a responsible person before they
 * influence an automated decision.</p>
 */
@Entity
@Table(name = "system_settings")
public class SystemSetting {

    @Id
    private UUID id;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "setting_key", nullable = false, length = 120)
    private String settingKey;

    @Column(nullable = false, length = 40)
    private String category;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Object value;

    @Column(name = "value_type", nullable = false, length = 20)
    private String valueType = "JSON";

    @Column(name = "requires_verification", nullable = false)
    private boolean requiresVerification;

    @Column(nullable = false)
    private int version = 1;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected SystemSetting() {
        // for JPA
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getSettingKey() {
        return settingKey;
    }

    public String getCategory() {
        return category;
    }

    public Object getValue() {
        return value;
    }

    public String getValueType() {
        return valueType;
    }

    public boolean isRequiresVerification() {
        return requiresVerification;
    }

    public int getVersion() {
        return version;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** Raw textual form, which is what a numeric or boolean setting is parsed from. */
    public String valueAsString() {
        return value == null ? null : String.valueOf(value);
    }

    public Map<String, Object> asMap() {
        return Map.of("key", settingKey, "value", value == null ? "" : value, "version", version);
    }
}
