package de.buerokratiecopilot.identity.domain;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * Assignment of a role to a membership, optionally time-boxed.
 *
 * <p>Time-boxing is what makes "temporary access" real: an expired assignment stops granting
 * permissions without any manual cleanup (spec §4).</p>
 */
@Entity
@Table(name = "membership_roles")
@IdClass(MembershipRole.Key.class)
public class MembershipRole {

    @Id
    @Column(name = "membership_id")
    private UUID membershipId;

    @Id
    @Column(name = "role_id")
    private UUID roleId;

    @Column(name = "granted_by")
    private UUID grantedBy;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt = Instant.now();

    @Column(name = "expires_at")
    private Instant expiresAt;

    protected MembershipRole() {
        // for JPA
    }

    public MembershipRole(UUID membershipId, UUID roleId, UUID grantedBy, Instant expiresAt) {
        this.membershipId = membershipId;
        this.roleId = roleId;
        this.grantedBy = grantedBy;
        this.expiresAt = expiresAt;
    }

    public UUID getMembershipId() {
        return membershipId;
    }

    public UUID getRoleId() {
        return roleId;
    }

    public UUID getGrantedBy() {
        return grantedBy;
    }

    public Instant getGrantedAt() {
        return grantedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isEffective(Instant now) {
        return expiresAt == null || now.isBefore(expiresAt);
    }

    /** Composite primary key. */
    public static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        private UUID membershipId;
        private UUID roleId;

        public Key() {
        }

        public Key(UUID membershipId, UUID roleId) {
            this.membershipId = membershipId;
            this.roleId = roleId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(membershipId, key.membershipId) && Objects.equals(roleId, key.roleId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(membershipId, roleId);
        }
    }
}
