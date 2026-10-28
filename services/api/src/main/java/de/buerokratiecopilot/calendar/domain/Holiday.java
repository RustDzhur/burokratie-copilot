package de.buerokratiecopilot.calendar.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One non-working day (spec §13.2).
 *
 * <p>{@code moveable} records that the day follows Easter rather than the calendar, which is what tells
 * a reviewer why 2026-04-03 is in the list and whether to check it again next year.</p>
 */
@Entity
@Table(name = "holidays")
public class Holiday {

    @Id
    private UUID id;

    @Column(name = "calendar_id", nullable = false)
    private UUID calendarId;

    @Column(name = "holiday_date", nullable = false)
    private LocalDate holidayDate;

    /** Localised: the same day is called different things in different languages. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> name;

    /** Half days exist in several regions and matter when a deadline falls on one. */
    @Column(name = "is_half_day", nullable = false)
    private boolean halfDay;

    @Column(name = "is_moveable", nullable = false)
    private boolean moveable;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Holiday() {
        // for JPA
    }

    public Holiday(UUID id, UUID calendarId, LocalDate holidayDate, String name, boolean halfDay,
                   boolean moveable) {
        this.id = id;
        this.calendarId = calendarId;
        this.holidayDate = holidayDate;
        this.name = Map.of("de", name);
        this.halfDay = halfDay;
        this.moveable = moveable;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCalendarId() {
        return calendarId;
    }

    public LocalDate getHolidayDate() {
        return holidayDate;
    }

    public Map<String, Object> getName() {
        return name;
    }

    public String nameIn(String language) {
        if (name == null) {
            return "";
        }
        Object localized = name.get(language);
        if (localized == null) {
            localized = name.get("de");
        }
        return localized == null ? "" : String.valueOf(localized);
    }

    public void setName(String name) {
        this.name = Map.of("de", name);
    }

    public boolean isHalfDay() {
        return halfDay;
    }

    public void setHalfDay(boolean halfDay) {
        this.halfDay = halfDay;
    }

    public boolean isMoveable() {
        return moveable;
    }

    public void setMoveable(boolean moveable) {
        this.moveable = moveable;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
