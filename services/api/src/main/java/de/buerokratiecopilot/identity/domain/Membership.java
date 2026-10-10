package de.buerokratiecopilot.identity.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Links a user to a tenant and carries the access state: status, ownership flag and an
 * optional validity window for temporary access (spec §4).
 */
@Entity
@Table(name = "memberships")
public class Membership {

    public enum Status {
        INVITED,
        ACTIVE,
        SUSPENDED,
        REVOKED,
        EXPIRED
    }

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "workspace_id")
    private UUID workspaceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Status status = Status.INVITED;

    @Column(name = "is_owner", nullable = false)
    private boolean owner;

    @Column(name = "invited_by")
    private UUID invitedBy;

    @Column(name = "joined_at")
    private Instant joinedAt;

    @Column(name = "access_valid_from")
    private Instant accessValidFrom;

    @Column(name = "access_valid_until")
    private Instant accessValidUntil;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by")
    private UUID revokedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Membership() {
        // for JPA
    }

    public Membership(UUID id, UUID tenantId, UUID userId, Status status) {
        this.id = id;
        this.tenantId = tenantId;
        this.userId = userId;
        this.status = status;
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

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(UUID workspaceId) {
        this.workspaceId = workspaceId;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public boolean isOwner() {
        return owner;
    }

    public void setOwner(boolean owner) {
        this.owner = owner;
    }

    public UUID getInvitedBy() {
        return invitedBy;
    }

    public void setInvitedBy(UUID invitedBy) {
        this.invitedBy = invitedBy;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(Instant joinedAt) {
        this.joinedAt = joinedAt;
    }

    public Instant getAccessValidFrom() {
        return accessValidFrom;
    }

    public void setAccessValidFrom(Instant accessValidFrom) {
        this.accessValidFrom = accessValidFrom;
    }

    public Instant getAccessValidUntil() {
        return accessValidUntil;
    }

    public void setAccessValidUntil(Instant accessValidUntil) {
        this.accessValidUntil = accessValidUntil;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void revoke(UUID by, Instant when) {
        this.status = Status.REVOKED;
        this.revokedBy = by;
        this.revokedAt = when;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * A membership grants access only while it is active and inside its validity window.
     * Expired temporary access must not keep working (spec §4).
     */
    public boolean isEffective(Instant now) {
        if (status != Status.ACTIVE || revokedAt != null) {
            return false;
        }
        if (accessValidFrom != null && now.isBefore(accessValidFrom)) {
            return false;
        }
        return accessValidUntil == null || now.isBefore(accessValidUntil);
    }
}
