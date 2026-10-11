package de.buerokratiecopilot.cases.domain;

import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A kind of case from the seeded taxonomy (spec §11).
 *
 * <p>The type decides which documents a case normally needs, so it is reference data rather than a
 * field a user types. That also means a new kind of matter is added by configuration.</p>
 */
@Entity
@Table(name = "case_types")
public class CaseType {

    @Id
    private UUID id;

    @Column(nullable = false, length = 80)
    private String code;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> description = Map.of();

    @Column(name = "default_priority", length = 20)
    private String defaultPriority;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 100;

    protected CaseType() {
        // for JPA
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public Map<String, Object> getName() {
        return name;
    }

    public String nameIn(String language) {
        if (name == null) {
            return code;
        }
        Object localized = name.get(language);
        if (localized == null) {
            localized = name.get("de");
        }
        return localized == null ? code : String.valueOf(localized);
    }

    public Map<String, Object> getDescription() {
        return description;
    }

    public String getDefaultPriority() {
        return defaultPriority;
    }

    public boolean isActive() {
        return active;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}
