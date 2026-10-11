package de.buerokratiecopilot.invoices.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
 * An invoice, incoming or outgoing (spec §14).
 *
 * <p>{@code bankDetailsChanged} and the validation state are first-class fields rather than notes: a
 * changed account number is the most common form of invoice fraud, and it has to be visible where the
 * invoice is, not in a log somebody could read.</p>
 *
 * <p>{@code paidAmount} is required by the schema and starts at zero, which is a statement ("nothing has
 * been paid yet") rather than a missing value.</p>
 */
@Entity
@Table(name = "invoices")
public class Invoice {

    public enum Direction {
        INCOMING, OUTGOING
    }

    /** The invoice kinds the schema allows. */
    public enum InvoiceType {
        INVOICE, CREDIT_NOTE, CORRECTION, SELF_BILLING, RECURRING
    }

    /** Where the record came from. */
    public enum Source {
        DOCUMENT, MANUAL, IMPORT, E_INVOICE, RECURRING
    }

    public enum Status {
        DRAFT, RECEIVED, IN_REVIEW, APPROVED, REJECTED, SCHEDULED, PARTIALLY_PAID, PAID, OVERDUE,
        CANCELLED, CREDITED
    }

    public enum PaymentStatus {
        UNPAID, PARTIALLY_PAID, PAID, OVERPAID, NOT_APPLICABLE
    }

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "case_id")
    private UUID caseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Direction direction = Direction.INCOMING;

    @Enumerated(EnumType.STRING)
    @Column(name = "invoice_type", nullable = false, length = 30)
    private InvoiceType invoiceType = InvoiceType.INVOICE;

    @Column(name = "invoice_number", nullable = false, length = 120)
    private String invoiceNumber;

    @Column(name = "supplier_id")
    private UUID supplierId;

    @Column(name = "contact_id")
    private UUID contactId;

    @Column(name = "contract_id")
    private UUID contractId;

    @Column(name = "purchase_order_id")
    private UUID purchaseOrderId;

    /** The document the invoice was read from, when it came in as a file. */
    @Column(name = "document_id")
    private UUID documentId;

    @Column(name = "issue_date")
    private LocalDate issueDate;

    @Column(name = "delivery_date")
    private LocalDate deliveryDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "payment_terms_days")
    private Integer paymentTermsDays;

    @Column(nullable = false, length = 3)
    private String currency = "EUR";

    @Column(name = "net_amount", precision = 14, scale = 2)
    private BigDecimal netAmount;

    @Column(name = "tax_amount", precision = 14, scale = 2)
    private BigDecimal taxAmount;

    @Column(name = "gross_amount", precision = 14, scale = 2)
    private BigDecimal grossAmount;

    @Column(name = "paid_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal paidAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Status status = Status.RECEIVED;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 30)
    private PaymentStatus paymentStatus = PaymentStatus.UNPAID;

    /** The result of the validation, as the validator produced it. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "validation_state", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> validationState = Map.of("state", "NOT_VALIDATED");

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "validation_flags", columnDefinition = "jsonb", nullable = false)
    private List<Map<String, Object>> validationFlags = List.of();

    /** True when the invoice asks to be paid into an account the supplier has not used before. */
    @Column(name = "bank_details_changed", nullable = false)
    private boolean bankDetailsChanged;

    @Column(length = 34)
    private String iban;

    @Column(length = 11)
    private String bic;

    @Column(name = "payment_reference")
    private String paymentReference;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Source source = Source.DOCUMENT;

    private String notes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> metadata = Map.of();

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Invoice() {
        // for JPA
    }

    public Invoice(UUID id, UUID tenantId, String invoiceNumber) {
        this.id = id;
        this.tenantId = tenantId;
        this.invoiceNumber = invoiceNumber;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public void setCaseId(UUID caseId) {
        this.caseId = caseId;
    }

    public Direction getDirection() {
        return direction;
    }

    public void setDirection(Direction direction) {
        this.direction = direction;
    }

    public InvoiceType getInvoiceType() {
        return invoiceType;
    }

    public void setInvoiceType(InvoiceType invoiceType) {
        this.invoiceType = invoiceType;
    }

    public String getInvoiceNumber() {
        return invoiceNumber;
    }

    public UUID getSupplierId() {
        return supplierId;
    }

    public void setSupplierId(UUID supplierId) {
        this.supplierId = supplierId;
    }

    public UUID getContactId() {
        return contactId;
    }

    public void setContactId(UUID contactId) {
        this.contactId = contactId;
    }

    public UUID getContractId() {
        return contractId;
    }

    public void setContractId(UUID contractId) {
        this.contractId = contractId;
    }

    public UUID getPurchaseOrderId() {
        return purchaseOrderId;
    }

    public void setPurchaseOrderId(UUID purchaseOrderId) {
        this.purchaseOrderId = purchaseOrderId;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public void setDocumentId(UUID documentId) {
        this.documentId = documentId;
    }

    public LocalDate getIssueDate() {
        return issueDate;
    }

    public void setIssueDate(LocalDate issueDate) {
        this.issueDate = issueDate;
    }

    public LocalDate getDeliveryDate() {
        return deliveryDate;
    }

    public void setDeliveryDate(LocalDate deliveryDate) {
        this.deliveryDate = deliveryDate;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    public Integer getPaymentTermsDays() {
        return paymentTermsDays;
    }

    public void setPaymentTermsDays(Integer paymentTermsDays) {
        this.paymentTermsDays = paymentTermsDays;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public BigDecimal getNetAmount() {
        return netAmount;
    }

    public void setNetAmount(BigDecimal netAmount) {
        this.netAmount = netAmount;
    }

    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public void setTaxAmount(BigDecimal taxAmount) {
        this.taxAmount = taxAmount;
    }

    public BigDecimal getGrossAmount() {
        return grossAmount;
    }

    public void setGrossAmount(BigDecimal grossAmount) {
        this.grossAmount = grossAmount;
    }

    public BigDecimal getPaidAmount() {
        return paidAmount;
    }

    /**
     * Records how much has been paid.
     *
     * <p>Only the payment service calls this, and only from an executed payment. A status that can be
     * set independently of the money is a status that will eventually be wrong.</p>
     */
    public void setPaidAmount(BigDecimal paidAmount) {
        if (paidAmount != null && paidAmount.signum() < 0) {
            throw new IllegalArgumentException("Ein gezahlter Betrag kann nicht negativ sein");
        }
        this.paidAmount = paidAmount == null ? BigDecimal.ZERO : paidAmount;
        this.updatedAt = Instant.now();
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public PaymentStatus getPaymentStatus() {
        return paymentStatus;
    }

    public void setPaymentStatus(PaymentStatus paymentStatus) {
        this.paymentStatus = paymentStatus;
    }

    public Map<String, Object> getValidationState() {
        return validationState;
    }

    public void setValidationState(Map<String, Object> validationState) {
        this.validationState = validationState == null ? Map.of() : validationState;
    }

    public List<Map<String, Object>> getValidationFlags() {
        return validationFlags;
    }

    public void setValidationFlags(List<Map<String, Object>> validationFlags) {
        this.validationFlags = validationFlags == null ? List.of() : validationFlags;
    }

    public boolean isBankDetailsChanged() {
        return bankDetailsChanged;
    }

    public void setBankDetailsChanged(boolean bankDetailsChanged) {
        this.bankDetailsChanged = bankDetailsChanged;
    }

    public String getIban() {
        return iban;
    }

    public void setIban(String iban) {
        this.iban = iban;
    }

    public String getBic() {
        return bic;
    }

    public void setBic(String bic) {
        this.bic = bic;
    }

    public String getPaymentReference() {
        return paymentReference;
    }

    public void setPaymentReference(String paymentReference) {
        this.paymentReference = paymentReference;
    }

    public UUID getApprovedBy() {
        return approvedBy;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public Source getSource() {
        return source;
    }

    public void setSource(Source source) {
        this.source = source;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
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

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    /** Whether the invoice may be posted automatically or needs a person first. */
    public boolean blocksAutomaticPosting() {
        return bankDetailsChanged
                || Boolean.TRUE.equals(validationState.get("blocksAutomaticPosting"));
    }

    public void approve(UUID userId) {
        this.status = Status.APPROVED;
        this.approvedBy = userId;
        this.approvedAt = Instant.now();
        this.updatedAt = Instant.now();
    }
}
