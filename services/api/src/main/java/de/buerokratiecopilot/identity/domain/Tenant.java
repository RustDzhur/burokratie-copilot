package de.buerokratiecopilot.identity.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * The hard isolation boundary of the platform (spec §3.2).
 *
 * <p>Only fields required by the domain logic are mapped; the remaining columns of
 * {@code tenants} (settings, retention overrides, …) are maintained through dedicated
 * projections so the entity never becomes a god object.</p>
 */
@Entity
@Table(name = "tenants")
public class Tenant {

    @Id
    private UUID id;

    @Column(nullable = false, length = 80)
    private String slug;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "account_type", nullable = false, length = 40)
    private String accountType;

    @Column(nullable = false, length = 30)
    private String status = "ACTIVE";

    @Column(name = "default_locale", nullable = false, length = 10)
    private String defaultLocale = "de";

    @Column(name = "default_time_zone", nullable = false, length = 60)
    private String defaultTimeZone = "Europe/Berlin";

    @Column(name = "data_region", nullable = false, length = 40)
    private String dataRegion = "eu-central";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Tenant() {
        // for JPA
    }

    public Tenant(UUID id, String slug, String name, String accountType) {
        this.id = id;
        this.slug = slug;
        this.name = name;
        this.accountType = accountType;
    }

    public UUID getId() {
        return id;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAccountType() {
        return accountType;
    }

    public void setAccountType(String accountType) {
        this.accountType = accountType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDefaultLocale() {
        return defaultLocale;
    }

    public void setDefaultLocale(String defaultLocale) {
        this.defaultLocale = defaultLocale;
    }

    public String getDefaultTimeZone() {
        return defaultTimeZone;
    }

    public void setDefaultTimeZone(String defaultTimeZone) {
        this.defaultTimeZone = defaultTimeZone;
    }

    public String getDataRegion() {
        return dataRegion;
    }

    public void setDataRegion(String dataRegion) {
        this.dataRegion = dataRegion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
