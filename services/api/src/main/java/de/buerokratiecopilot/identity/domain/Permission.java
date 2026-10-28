package de.buerokratiecopilot.identity.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A single permission of the catalogue, expressed as {@code area.action}
 * (for example {@code documents.view}, {@code finance.execute_payment}).
 *
 * <p>Permissions are granted to roles, never directly to users, which keeps the authorization
 * matrix auditable (spec §4).</p>
 */
@Entity
@Table(name = "permissions")
public class Permission {

    @Id
    private UUID id;

    @Column(nullable = false, length = 80)
    private String code;

    @Column(nullable = false, length = 40)
    private String area;

    @Column(nullable = false, length = 40)
    private String action;

    protected Permission() {
        // for JPA
    }

    public Permission(UUID id, String code) {
        this.id = id;
        this.code = code;
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getArea() {
        return area;
    }

    public String getAction() {
        return action;
    }
}
