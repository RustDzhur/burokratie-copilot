package de.buerokratiecopilot.cases.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Who may see a case, and how much they may do with it (spec §11.1, §12).
 *
 * <p>Access to a case is granted per case and per person, not per role. That is what the specification
 * asks for and it is also the only model that survives reality: a tax matter is not visible to the
 * whole company because everyone has the same job title.</p>
 *
 * <p>A participant is either a user of the tenant or an external contact — a lawyer, an advisor or the
 * counterparty itself. The two are mutually exclusive in the schema, so an external party can be
 * recorded without inventing a user account for them.</p>
 */
@Entity
@Table(name = "case_participants")
public class CaseParticipant {

    /** What the participant means to the case, which is not the same as what they may do. */
    public enum Role {
        OWNER, RESPONSIBLE, PARTICIPANT, OBSERVER, APPROVER, EXTERNAL_ADVISOR, LEGAL_ADVISOR, COUNTERPARTY
    }

    /** What the participant may do; ordered, so a check is a comparison rather than a set lookup. */
    public enum AccessLevel {
        READ, COMMENT, WRITE, APPROVE, FULL;

        public boolean allows(AccessLevel required) {
            return this.ordinal() >= required.ordinal();
        }
    }

    @Id
    private UUID id;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "contact_id")
    private UUID contactId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Role role = Role.PARTICIPANT;

    @Enumerated(EnumType.STRING)
    @Column(name = "access_level", nullable = false, length = 20)
    private AccessLevel accessLevel = AccessLevel.READ;

    @Column(name = "granted_by")
    private UUID grantedBy;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt = Instant.now();

    /** Time limited access, for an advisor engaged for one matter. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected CaseParticipant() {
        // for JPA
    }

    public CaseParticipant(UUID id, UUID tenantId, UUID caseId, UUID userId, UUID contactId, Role role,
                           AccessLevel accessLevel) {
        if ((userId == null) == (contactId == null)) {
            throw new IllegalArgumentException(
                    "A participant is either a user of the tenant or an external contact, never both and never neither");
        }
        this.id = id;
        this.tenantId = tenantId;
        this.caseId = caseId;
        this.userId = userId;
        this.contactId = contactId;
        this.role = role;
        this.accessLevel = accessLevel;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getContactId() {
        return contactId;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public AccessLevel getAccessLevel() {
        return accessLevel;
    }

    public void setAccessLevel(AccessLevel accessLevel) {
        this.accessLevel = accessLevel;
    }

    public UUID getGrantedBy() {
        return grantedBy;
    }

    public void setGrantedBy(UUID grantedBy) {
        this.grantedBy = grantedBy;
    }

    public Instant getGrantedAt() {
        return grantedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void revoke() {
        this.revokedAt = Instant.now();
    }

    /** Whether the grant is in force right now. An expired or revoked grant is not access. */
    public boolean isActive(Instant now) {
        return revokedAt == null && (expiresAt == null || expiresAt.isAfter(now));
    }
}
