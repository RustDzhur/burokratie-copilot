package de.buerokratiecopilot.approvals.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
 * A payment, prepared and then confirmed (spec §15).
 *
 * <p>The status separates the two acts the specification insists on keeping apart: preparing a payment
 * and confirming it. A record in {@code PREPARED} is a proposal; only a person's confirmation moves it
 * on, and the base configuration never executes anything by itself.</p>
 *
 * <p>{@code idempotencyKey} is what makes a repeated confirmation harmless. The key is derived from the
 * invoice and the amount, so confirming twice produces one payment — the alternative is paying a
 * supplier twice because somebody's browser retried a request.</p>
 */
@Entity
@Table(name = "payments")
public class Payment {

    public enum Direction {
        INCOMING, OUTGOING
    }

    public enum Method {
        BANK_TRANSFER, DIRECT_DEBIT, CARD, CASH, CHEQUE, OTHER
    }

    public enum Status {
        PLANNED, PREPARED, AWAITING_APPROVAL, APPROVED, EXECUTED, SETTLED, FAILED, CANCELLED, REJECTED
    }

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Direction direction = Direction.OUTGOING;

    @Column(name = "invoice_id")
    private UUID invoiceId;

    @Column(name = "case_id")
    private UUID caseId;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency = "EUR";

    @Column(name = "payment_date")
    private LocalDate paymentDate;

    @Column(name = "value_date")
    private LocalDate valueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Method method = Method.BANK_TRANSFER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Status status = Status.PREPARED;

    private String reference;

    @Column(name = "counterparty_name")
    private String counterpartyName;

    @Column(name = "counterparty_iban", length = 34)
    private String counterpartyIban;

    @Column(name = "external_id", length = 120)
    private String externalId;

    /** Proof that the payment happened: a bank statement line, a receipt, a confirmation. */
    @Column(name = "evidence_blob_id")
    private UUID evidenceBlobId;

    @Column(name = "confirmed_by")
    private UUID confirmedBy;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "executed_by")
    private UUID executedBy;

    @Column(name = "executed_at")
    private Instant executedAt;

    @Column(name = "failure_reason")
    private String failureReason;

    /** Derived from what is being paid, so a repeated confirmation cannot create a second payment. */
    @Column(name = "idempotency_key", length = 200)
    private String idempotencyKey;

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

    protected Payment() {
        // for JPA
    }

    public Payment(UUID id, UUID tenantId, BigDecimal amount, String currency) {
        this.id = id;
        this.tenantId = tenantId;
        this.amount = amount;
        this.currency = currency;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public Direction getDirection() {
        return direction;
    }

    public void setDirection(Direction direction) {
        this.direction = direction;
    }

    public UUID getInvoiceId() {
        return invoiceId;
    }

    public void setInvoiceId(UUID invoiceId) {
        this.invoiceId = invoiceId;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public void setCaseId(UUID caseId) {
        this.caseId = caseId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public LocalDate getPaymentDate() {
        return paymentDate;
    }

    public void setPaymentDate(LocalDate paymentDate) {
        this.paymentDate = paymentDate;
    }

    public Method getMethod() {
        return method;
    }

    public void setMethod(Method method) {
        this.method = method;
    }

    public Status getStatus() {
        return status;
    }

    public String getReference() {
        return reference;
    }

    public void setReference(String reference) {
        this.reference = reference;
    }

    public String getCounterpartyName() {
        return counterpartyName;
    }

    public void setCounterpartyName(String counterpartyName) {
        this.counterpartyName = counterpartyName;
    }

    public String getCounterpartyIban() {
        return counterpartyIban;
    }

    public void setCounterpartyIban(String counterpartyIban) {
        this.counterpartyIban = counterpartyIban;
    }

    public UUID getEvidenceBlobId() {
        return evidenceBlobId;
    }

    public void setEvidenceBlobId(UUID evidenceBlobId) {
        this.evidenceBlobId = evidenceBlobId;
    }

    public UUID getConfirmedBy() {
        return confirmedBy;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public UUID getExecutedBy() {
        return executedBy;
    }

    public Instant getExecutedAt() {
        return executedAt;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
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

    /** Marks the payment as prepared and awaiting a decision. */
    public void awaitApproval() {
        this.status = Status.AWAITING_APPROVAL;
        this.updatedAt = Instant.now();
    }

    /** A person confirms the payment. */
    public void confirm(UUID userId) {
        this.status = Status.APPROVED;
        this.confirmedBy = userId;
        this.confirmedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /** Records that the payment was handed to a bank or a provider. */
    public void execute(UUID userId, String externalReference) {
        this.status = Status.EXECUTED;
        this.executedBy = userId;
        this.executedAt = Instant.now();
        this.externalId = externalReference;
        this.updatedAt = Instant.now();
    }

    public boolean isConfirmed() {
        return confirmedAt != null;
    }

    public boolean isExecuted() {
        return status == Status.EXECUTED || status == Status.SETTLED;
    }
}
