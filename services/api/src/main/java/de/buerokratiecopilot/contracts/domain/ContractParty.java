package de.buerokratiecopilot.contracts.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A party to a contract (spec §13.1).
 *
 * <p>The name is stored as it is written in the document, next to the contact it was matched to. The
 * two are not the same: "Muster GmbH, vertreten durch …" is what the paper says, and the contact is
 * which record in the platform it refers to. Replacing the first with the second would lose what the
 * contract actually names.</p>
 */
@Entity
@Table(name = "contract_parties")
public class ContractParty {

    @Id
    private UUID id;

    @Column(name = "contract_id", nullable = false)
    private UUID contractId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "contact_id")
    private UUID contactId;

    @Column(name = "organization_id")
    private UUID organizationId;

    /** The roles the schema allows. */
    @Column(name = "party_role", nullable = false, length = 40)
    private String partyRole = "COUNTERPARTY";

    /** The name as the contract writes it. */
    @Column(name = "party_name", nullable = false, length = 300)
    private String partyName;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ContractParty() {
        // for JPA
    }

    public ContractParty(UUID id, UUID tenantId, UUID contractId, String partyRole, String partyName) {
        this.id = id;
        this.tenantId = tenantId;
        this.contractId = contractId;
        this.partyRole = partyRole;
        this.partyName = partyName;
    }

    public UUID getId() {
        return id;
    }

    public UUID getContractId() {
        return contractId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getContactId() {
        return contactId;
    }

    public void setContactId(UUID contactId) {
        this.contactId = contactId;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(UUID organizationId) {
        this.organizationId = organizationId;
    }

    public String getPartyRole() {
        return partyRole;
    }

    public void setPartyRole(String partyRole) {
        this.partyRole = partyRole;
    }

    public String getPartyName() {
        return partyName;
    }

    public boolean isPrimary() {
        return primary;
    }

    public void setPrimary(boolean primary) {
        this.primary = primary;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
