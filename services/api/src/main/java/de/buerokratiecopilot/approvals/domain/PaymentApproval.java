package de.buerokratiecopilot.approvals.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One level of a payment approval (spec §15).
 *
 * <p>Four-eyes control is not "somebody approved it": with {@code requireSecondPerson} the rule demands
 * two different people, and this table is where each level records who decided it. A single approval
 * field could not express that, and a rule the data cannot hold is a rule the platform does not
 * enforce.</p>
 */
@Entity
@Table(name = "payment_approvals")
public class PaymentApproval {

    public enum Status {
        PENDING, APPROVED, REJECTED, SKIPPED, EXPIRED
    }

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "invoice_id")
    private UUID invoiceId;

    @Column(name = "rule_id")
    private UUID ruleId;

    /** The general approval this level belongs to. */
    @Column(name = "approval_id")
    private UUID approvalId;

    @Column(name = "approval_level", nullable = false)
    private int approvalLevel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "approver_user_id")
    private UUID approverUserId;

    @Column(name = "decided_at")
    private Instant decidedAt;

    private String comment;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected PaymentApproval() {
        // for JPA
    }

    public PaymentApproval(UUID id, UUID tenantId, UUID paymentId, UUID ruleId, int approvalLevel) {
        this.id = id;
        this.tenantId = tenantId;
        this.paymentId = paymentId;
        this.ruleId = ruleId;
        this.approvalLevel = approvalLevel;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(UUID paymentId) {
        this.paymentId = paymentId;
    }

    public UUID getInvoiceId() {
        return invoiceId;
    }

    public void setInvoiceId(UUID invoiceId) {
        this.invoiceId = invoiceId;
    }

    public UUID getRuleId() {
        return ruleId;
    }

    public UUID getApprovalId() {
        return approvalId;
    }

    public void setApprovalId(UUID approvalId) {
        this.approvalId = approvalId;
    }

    public int getApprovalLevel() {
        return approvalLevel;
    }

    public Status getStatus() {
        return status;
    }

    public UUID getApproverUserId() {
        return approverUserId;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getComment() {
        return comment;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isPending() {
        return status == Status.PENDING;
    }

    public void decide(Status decision, UUID approverId, String comment) {
        if (!isPending()) {
            throw new IllegalStateException("Diese Freigabestufe ist bereits entschieden");
        }
        if (decision != Status.APPROVED && decision != Status.REJECTED) {
            throw new IllegalArgumentException(
                    "Eine Freigabestufe wird freigegeben oder abgelehnt; " + decision
                            + " ist keine Entscheidung");
        }
        this.status = decision;
        this.approverUserId = approverId;
        this.decidedAt = Instant.now();
        this.comment = comment;
    }

    /**
     * Marks the level as no longer relevant, without a decider.
     *
     * <p>The schema requires it: only an approval or a rejection carries a person and a time. A skipped
     * level was never decided, and recording a name against it would put somebody's signature on a
     * decision they did not make.</p>
     */
    public void skip(String reason) {
        if (!isPending()) {
            return;
        }
        this.status = Status.SKIPPED;
        this.approverUserId = null;
        this.decidedAt = null;
        this.comment = reason;
    }
}
