package de.buerokratiecopilot.calendar.domain;

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
 * A calendar of non-working days (spec §13.2, §10.4).
 *
 * <p>The platform ships <strong>no</strong> holiday data. Which days are public holidays is decided by
 * law, differs by country and region, and changes; a bundled list would be wrong somewhere on the day
 * it was written and silently wrong everywhere it was not checked. The calendar is therefore a
 * container the operator fills from a source they trust.</p>
 *
 * <p>{@code region} exists because the answer differs within a country — the same date is a holiday in
 * one federal state and a working day in the next.</p>
 */
@Entity
@Table(name = "holiday_calendars")
public class HolidayCalendar {

    @Id
    private UUID id;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(nullable = false, length = 60)
    private String code;

    /** Localised, like the taxonomy names: a calendar is shown in the language of its reader. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> name;

    /** ISO 3166-1 alpha-2; the schema checks the shape. */
    @Column(name = "country_code", nullable = false, length = 2)
    private String countryCode;

    private String region;

    @Column(name = "time_zone", nullable = false, length = 60)
    private String timeZone = "Europe/Berlin";

    @Column(name = "is_default", nullable = false)
    private boolean defaultCalendar;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected HolidayCalendar() {
        // for JPA
    }

    public HolidayCalendar(UUID id, UUID tenantId, String code, String name, String countryCode) {
        this.id = id;
        this.tenantId = tenantId;
        this.code = code;
        this.name = Map.of("de", name);
        this.countryCode = countryCode;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getCode() {
        return code;
    }

    public Map<String, Object> getName() {
        return name;
    }

    /** The name in a language, falling back to German and then to the code. */
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

    public String getCountryCode() {
        return countryCode;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }

    public boolean isDefaultCalendar() {
        return defaultCalendar;
    }

    public void setDefaultCalendar(boolean defaultCalendar) {
        this.defaultCalendar = defaultCalendar;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** How the calendar is named in a user interface, including the region when there is one. */
    public String displayName() {
        String label = nameIn("de");
        return region == null || region.isBlank() ? label + " (" + countryCode + ")"
                : label + " (" + countryCode + "-" + region + ")";
    }
}
