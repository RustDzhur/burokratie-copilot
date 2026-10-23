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
 * A working area inside a tenant. The {@code mode} is historical metadata: there is one kind of cabinet in the
 * Kanzlei edition (§4.3), and every workspace created now is a {@link Mode#BUSINESS} one. The other values remain
 * because the database check constraint and rows written before V83 still use them.
 */
@Entity
@Table(name = "workspaces")
public class Workspace {

    public enum Mode {
        PRIVATE,
        FAMILY,
        BUSINESS,
        ADVISOR,
        PUBLIC_SECTOR
    }

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 30)
    private String mode;

    @Column(name = "is_default", nullable = false)
    private boolean defaultWorkspace;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Workspace() {
        // for JPA
    }

    public Workspace(UUID id, UUID tenantId, String name, Mode mode) {
        this.id = id;
        this.tenantId = tenantId;
        this.name = name;
        this.mode = mode.name();
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(UUID organizationId) {
        this.organizationId = organizationId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode.name();
    }

    public boolean isDefaultWorkspace() {
        return defaultWorkspace;
    }

    public void setDefaultWorkspace(boolean defaultWorkspace) {
        this.defaultWorkspace = defaultWorkspace;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
