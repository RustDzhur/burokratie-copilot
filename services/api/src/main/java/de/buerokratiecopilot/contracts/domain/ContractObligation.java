package de.buerokratiecopilot.contracts.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Something a contract obliges somebody to do (spec §13.1, D-002).
 *
 * <p>An obligation is what turns a contract from a document into a calendar: a payment falls due, a
 * report has to be delivered, a renewal decision has to be made. {@code reminderDaysBefore} is the
 * tenant's rule for when to warn, not something the platform decides — how much warning a person needs
 * is their business.</p>
 */
@Entity
@Table(name = "contract_obligations")
public class ContractObligation {

    /** The kinds of obligation the schema allows. */
    public enum ObligationType {
        PAYMENT, NOTICE, REPORT, INSPECTION, RENEWAL_DECISION, DOCUMENT_DELIVERY, OTHER
    }

    public enum Recurrence {
        ONE_TIME, MONTHLY, QUARTERLY, SEMIANNUAL, YEARLY
    }

    public enum Status {
        OPEN, DUE, FULFILLED, MISSED, CANCELLED
    }

    @Id
    private UUID id;

    @Column(name = "contract_id", nullable = false)
    private UUID contractId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "obligation_type", nullable = false, length = 30)
    private ObligationType obligationType = ObligationType.OTHER;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Recurrence recurrence = Recurrence.ONE_TIME;

    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency = "EUR";

    @Column(name = "responsible_user_id")
    private UUID responsibleUserId;

    /** How many days before the due date the tenant wants to be warned. */
    @Column(name = "reminder_days_before")
    private Integer reminderDaysBefore;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.OPEN;

    @Column(name = "fulfilled_at")
    private Instant fulfilledAt;

    @Column(name = "fulfilled_by")
    private UUID fulfilledBy;

    /** The clause the obligation was read from, so it can be checked against the document. */
    @Column(name = "contract_clause_id")
    private UUID contractClauseId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected ContractObligation() {
        // for JPA
    }

    public ContractObligation(UUID id, UUID tenantId, UUID contractId, ObligationType obligationType,
                              String title) {
        this.id = id;
        this.tenantId = tenantId;
        this.contractId = contractId;
        this.obligationType = obligationType;
        this.title = title;
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

    public ObligationType getObligationType() {
        return obligationType;
    }

    public String getTitle() {
        return title;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    public Recurrence getRecurrence() {
        return recurrence;
    }

    public void setRecurrence(Recurrence recurrence) {
        this.recurrence = recurrence;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public UUID getResponsibleUserId() {
        return responsibleUserId;
    }

    public void setResponsibleUserId(UUID responsibleUserId) {
        this.responsibleUserId = responsibleUserId;
    }

    public Integer getReminderDaysBefore() {
        return reminderDaysBefore;
    }

    public void setReminderDaysBefore(Integer reminderDaysBefore) {
        this.reminderDaysBefore = reminderDaysBefore;
    }

    public Status getStatus() {
        return status;
    }

    public UUID getFulfilledBy() {
        return fulfilledBy;
    }

    public Instant getFulfilledAt() {
        return fulfilledAt;
    }

    public UUID getContractClauseId() {
        return contractClauseId;
    }

    public void setContractClauseId(UUID contractClauseId) {
        this.contractClauseId = contractClauseId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isOpen() {
        return status == Status.OPEN || status == Status.DUE || status == Status.MISSED;
    }

    /**
     * Whether the reminder window has been reached on a given day.
     *
     * <p>Only an obligation with a due date and a configured warning period can produce a reminder:
     * the platform does not guess how much warning somebody wants.</p>
     */
    public boolean isReminderDue(LocalDate today) {
        if (!isOpen() || dueDate == null || reminderDaysBefore == null) {
            return false;
        }
        LocalDate reminderDate = dueDate.minusDays(reminderDaysBefore);
        return !today.isBefore(reminderDate) && !today.isAfter(dueDate);
    }

    /** Marks the obligation fulfilled by a person. */
    public void fulfil(UUID userId) {
        this.status = Status.FULFILLED;
        this.fulfilledBy = userId;
        this.fulfilledAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /** Marks an obligation whose date has passed without being fulfilled. */
    public void markMissed() {
        this.status = Status.MISSED;
        this.updatedAt = Instant.now();
    }
}
