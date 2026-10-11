package de.buerokratiecopilot.contracts.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A version of a contract (spec §13.1, D-002).
 *
 * <p>Versions exist so a change can be seen rather than remembered. An amendment, a price adjustment or
 * a re-signed copy is a new version, and the comparison between two of them is what turns "something
 * changed" into "this clause now says that".</p>
 */
@Entity
@Table(name = "contract_versions")
public class ContractVersion {

    @Id
    private UUID id;

    @Column(name = "contract_id", nullable = false)
    private UUID contractId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** The document this version was taken from, when there is one. */
    @Column(name = "document_id")
    private UUID documentId;

    @Column(name = "document_version_id")
    private UUID documentVersionId;

    @Column(name = "version_label", nullable = false, length = 60)
    private String versionLabel;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    private String changeNote;

    /** Exactly one version of a contract is current; the service keeps that true. */
    @Column(name = "is_current", nullable = false)
    private boolean current;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ContractVersion() {
        // for JPA
    }

    public ContractVersion(UUID id, UUID tenantId, UUID contractId, String versionLabel) {
        this.id = id;
        this.tenantId = tenantId;
        this.contractId = contractId;
        this.versionLabel = versionLabel;
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

    public UUID getDocumentId() {
        return documentId;
    }

    public void setDocumentId(UUID documentId) {
        this.documentId = documentId;
    }

    public UUID getDocumentVersionId() {
        return documentVersionId;
    }

    public void setDocumentVersionId(UUID documentVersionId) {
        this.documentVersionId = documentVersionId;
    }

    public String getVersionLabel() {
        return versionLabel;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(LocalDate effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public String getChangeNote() {
        return changeNote;
    }

    public void setChangeNote(String changeNote) {
        this.changeNote = changeNote;
    }

    public boolean isCurrent() {
        return current;
    }

    public void setCurrent(boolean current) {
        this.current = current;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(UUID createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
