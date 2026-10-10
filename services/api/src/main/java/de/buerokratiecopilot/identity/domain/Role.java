package de.buerokratiecopilot.identity.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A role from the RBAC catalogue (spec §4).
 *
 * <p>{@code tenantId == null} marks a system role that every tenant can use; a tenant may add
 * its own roles later, which is the extension point towards ABAC.</p>
 */
@Entity
@Table(name = "roles")
public class Role {

    /** Codes of the ten base roles required by spec §4. */
    public static final String OWNER = "OWNER";
    public static final String ADMIN = "ADMIN";
    public static final String MANAGER = "MANAGER";
    public static final String EMPLOYEE = "EMPLOYEE";
    public static final String ACCOUNTANT = "ACCOUNTANT";
    public static final String LEGAL_ADVISOR = "LEGAL_ADVISOR";
    public static final String EXTERNAL_ADVISOR = "EXTERNAL_ADVISOR";
    public static final String READ_ONLY = "READ_ONLY";
    public static final String FAMILY_MEMBER = "FAMILY_MEMBER";
    public static final String AUDITOR = "AUDITOR";

    @Id
    private UUID id;

    @Column(nullable = false, length = 60)
    private String code;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "is_system", nullable = false)
    private boolean system;

    @Column(nullable = false)
    private int rank = 100;

    protected Role() {
        // for JPA
    }

    public Role(UUID id, String code) {
        this.id = id;
        this.code = code;
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public boolean isSystem() {
        return system;
    }

    public int getRank() {
        return rank;
    }
}
