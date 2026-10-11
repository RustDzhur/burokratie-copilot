package de.buerokratiecopilot.einvoicing.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One electronic invoice the platform received (spec §14, D-005).
 *
 * <p>{@code originalXmlBlobId} points at the stored file, which is kept byte for byte. The record holds
 * what was read and what was concluded; the file holds what the supplier sent. Keeping them apart is
 * what allows a misread invoice to be read again after the reader is fixed.</p>
 *
 * <p>{@code validationStatus} has a state for "the platform cannot read this format" as distinct from
 * "the file is wrong". A supplier using a syntax the platform does not support is a fact the operator
 * needs, and it is not the supplier's mistake.</p>
 */
@Entity
@Table(name = "e_invoice_records")
public class EInvoiceRecord {

    /** The formats the schema allows. */
    public enum Format {
        XRECHNUNG, ZUGFERD, OTHER
    }

    public enum ValidationStatus {
        NOT_VALIDATED, VALID, VALID_WITH_WARNINGS, INVALID, NOT_SUPPORTED
    }

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "document_id")
    private UUID documentId;

    @Column(name = "invoice_id")
    private UUID invoiceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Format format;

    /** The syntax as the file declares it: UBL or CII, or the reason it is neither. */
    @Column(length = 40)
    private String syntax;

    @Column(length = 120)
    private String profile;

    @Column(name = "specification_version", length = 60)
    private String specificationVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "validation_status", nullable = false, length = 30)
    private ValidationStatus validationStatus = ValidationStatus.NOT_VALIDATED;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "validation_results", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> validationResults = Map.of();

    /** Which version of the rule set produced the result, so a later change can be told apart. */
    @Column(name = "ruleset_version", length = 60)
    private String rulesetVersion;

    /** The stored file, unchanged. */
    @Column(name = "original_xml_blob_id")
    private UUID originalXmlBlobId;

    @Column(name = "extracted_at")
    private Instant extractedAt;

    @Column(name = "validated_at")
    private Instant validatedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected EInvoiceRecord() {
        // for JPA
    }

    public EInvoiceRecord(UUID id, UUID tenantId, Format format) {
        this.id = id;
        this.tenantId = tenantId;
        this.format = format;
    }

    public UUID getId() {
        return id;
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

    public UUID getInvoiceId() {
        return invoiceId;
    }

    public void setInvoiceId(UUID invoiceId) {
        this.invoiceId = invoiceId;
    }

    public Format getFormat() {
        return format;
    }

    public void setFormat(Format format) {
        this.format = format;
    }

    public String getSyntax() {
        return syntax;
    }

    public void setSyntax(String syntax) {
        this.syntax = syntax;
    }

    public String getProfile() {
        return profile;
    }

    public void setProfile(String profile) {
        this.profile = profile;
    }

    public String getSpecificationVersion() {
        return specificationVersion;
    }

    public void setSpecificationVersion(String specificationVersion) {
        this.specificationVersion = specificationVersion;
    }

    public ValidationStatus getValidationStatus() {
        return validationStatus;
    }

    public void setValidationStatus(ValidationStatus validationStatus) {
        this.validationStatus = validationStatus;
    }

    public Map<String, Object> getValidationResults() {
        return validationResults;
    }

    public void setValidationResults(Map<String, Object> validationResults) {
        this.validationResults = validationResults == null ? Map.of() : validationResults;
    }

    public String getRulesetVersion() {
        return rulesetVersion;
    }

    public void setRulesetVersion(String rulesetVersion) {
        this.rulesetVersion = rulesetVersion;
    }

    public UUID getOriginalXmlBlobId() {
        return originalXmlBlobId;
    }

    public void setOriginalXmlBlobId(UUID originalXmlBlobId) {
        this.originalXmlBlobId = originalXmlBlobId;
    }

    public Instant getExtractedAt() {
        return extractedAt;
    }

    public void setExtractedAt(Instant extractedAt) {
        this.extractedAt = extractedAt;
    }

    public Instant getValidatedAt() {
        return validatedAt;
    }

    public void setValidatedAt(Instant validatedAt) {
        this.validatedAt = validatedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** Whether the platform could read the file at all. */
    public boolean isSupported() {
        return validationStatus != ValidationStatus.NOT_SUPPORTED;
    }

    /** The errors recorded against the file, for a caller that wants them as a list. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> errors() {
        Object errors = validationResults.get("errors");
        return errors instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }
}
