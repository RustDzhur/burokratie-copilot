package de.buerokratiecopilot.identity.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * Join entity between roles and permissions (spec §4).
 *
 * <p>The table has no surrogate key; the pair (role, permission) is the identity. It is mapped
 * as an entity so that permission lookups can be expressed as a single JPQL join instead of
 * relying on a native query.</p>
 */
@Entity
@Table(name = "role_permissions")
@IdClass(RolePermission.Key.class)
public class RolePermission {

    @Id
    @Column(name = "role_id")
    private UUID roleId;

    @Id
    @Column(name = "permission_id")
    private UUID permissionId;

    protected RolePermission() {
        // for JPA
    }

    public UUID getRoleId() {
        return roleId;
    }

    public UUID getPermissionId() {
        return permissionId;
    }

    /** Composite primary key. */
    public static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        private UUID roleId;
        private UUID permissionId;

        public Key() {
        }

        public Key(UUID roleId, UUID permissionId) {
            this.roleId = roleId;
            this.permissionId = permissionId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(roleId, key.roleId) && Objects.equals(permissionId, key.permissionId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(roleId, permissionId);
        }
    }
}
