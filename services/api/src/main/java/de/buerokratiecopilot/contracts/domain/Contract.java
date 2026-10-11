package de.buerokratiecopilot.contracts.domain;

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
 * A contract, with its terms as data (spec §13.1).
 *
 * <p>The entity holds what was <em>read</em> from the document: dates, amounts, renewal periods, each
 * with the clause it came from. It holds no legal conclusions. Whether an automatically renewing
 * contract is worth cancelling, or whether a notice period is valid, are questions the platform
 * answers only when a rule says so — and a rule is configuration a person verified, never an
 * assumption baked into the code.</p>
 *
 * <p>{@code dataVerifiedBy} records that a human checked the extracted values against the paper. Until
 * then the values are what the text says, which is a different claim from what the contract means.</p>
 */
@Entity
@Table(name = "contracts")
public class Contract {

    /** Lifecycle of the record, not a legal assessment. */
    public enum Status {
        DRAFT, ACTIVE, EXPIRING, EXPIRED, TERMINATED, ARCHIVED
    }

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "case_id")
    private UUID caseId;

    @Column(name = "counterparty_contact_id")
    private UUID counterpartyContactId;

    @Column(name = "contract_number", length = 80)
    private String contractNumber;

    @Column(nullable = false, length = 300)
    private String title;

    /** Mandatory in the schema: a contract is of some kind, and "unknown" is a kind too. */
    @Column(name = "contract_type", nullable = false, length = 60)
    private String contractType = "OTHER";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.DRAFT;

    private String description;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "signed_date")
    private LocalDate signedDate;

    @Column(name = "auto_renewal")
    private Boolean autoRenewal;

    @Column(name = "renewal_period_months")
    private Integer renewalPeriodMonths;

    @Column(name = "notice_period_value")
    private Integer noticePeriodValue;

    @Column(name = "notice_period_unit", length = 20)
    private String noticePeriodUnit;

    @Column(name = "notice_deadline_date")
    private LocalDate noticeDeadlineDate;

    /** The rule that produced the notice deadline, so the calculation stays reviewable. */
    @Column(name = "notice_deadline_rule_id")
    private UUID noticeDeadlineRuleId;

    @Column(name = "cost_amount", precision = 14, scale = 2)
    private BigDecimal costAmount;

    @Column(name = "cost_currency", nullable = false, length = 3)
    private String costCurrency = "EUR";

    @Column(name = "cost_interval", length = 20)
    private String costInterval;

    private String priceChangeClause;

    /** The document the record was built from. */
    @Column(name = "source_document_id")
    private UUID sourceDocumentId;

    @Column(name = "data_verified_by")
    private UUID dataVerifiedBy;

    @Column(name = "data_verified_at")
    private Instant dataVerifiedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<String> tags = List.of();

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

    protected Contract() {
        // for JPA
    }

    public Contract(UUID id, UUID tenantId, String title) {
        this.id = id;
        this.tenantId = tenantId;
        this.title = title;
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

    public UUID getCounterpartyContactId() {
        return counterpartyContactId;
    }

    public void setCounterpartyContactId(UUID counterpartyContactId) {
        this.counterpartyContactId = counterpartyContactId;
    }

    public String getContractNumber() {
        return contractNumber;
    }

    public void setContractNumber(String contractNumber) {
        this.contractNumber = contractNumber;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContractType() {
        return contractType;
    }

    public void setContractType(String contractType) {
        this.contractType = contractType;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public LocalDate getSignedDate() {
        return signedDate;
    }

    public void setSignedDate(LocalDate signedDate) {
        this.signedDate = signedDate;
    }

    public Boolean getAutoRenewal() {
        return autoRenewal;
    }

    public void setAutoRenewal(Boolean autoRenewal) {
        this.autoRenewal = autoRenewal;
    }

    public Integer getRenewalPeriodMonths() {
        return renewalPeriodMonths;
    }

    public void setRenewalPeriodMonths(Integer renewalPeriodMonths) {
        this.renewalPeriodMonths = renewalPeriodMonths;
    }

    public Integer getNoticePeriodValue() {
        return noticePeriodValue;
    }

    public void setNoticePeriodValue(Integer noticePeriodValue) {
        this.noticePeriodValue = noticePeriodValue;
    }

    public String getNoticePeriodUnit() {
        return noticePeriodUnit;
    }

    public void setNoticePeriodUnit(String noticePeriodUnit) {
        this.noticePeriodUnit = noticePeriodUnit;
    }

    public LocalDate getNoticeDeadlineDate() {
        return noticeDeadlineDate;
    }

    public void setNoticeDeadlineDate(LocalDate noticeDeadlineDate) {
        this.noticeDeadlineDate = noticeDeadlineDate;
    }

    public UUID getNoticeDeadlineRuleId() {
        return noticeDeadlineRuleId;
    }

    public void setNoticeDeadlineRuleId(UUID noticeDeadlineRuleId) {
        this.noticeDeadlineRuleId = noticeDeadlineRuleId;
    }

    public BigDecimal getCostAmount() {
        return costAmount;
    }

    public void setCostAmount(BigDecimal costAmount) {
        this.costAmount = costAmount;
    }

    public String getCostCurrency() {
        return costCurrency;
    }

    public void setCostCurrency(String costCurrency) {
        this.costCurrency = costCurrency;
    }

    public String getCostInterval() {
        return costInterval;
    }

    public void setCostInterval(String costInterval) {
        this.costInterval = costInterval;
    }

    public String getPriceChangeClause() {
        return priceChangeClause;
    }

    public void setPriceChangeClause(String priceChangeClause) {
        this.priceChangeClause = priceChangeClause;
    }

    public UUID getSourceDocumentId() {
        return sourceDocumentId;
    }

    public void setSourceDocumentId(UUID sourceDocumentId) {
        this.sourceDocumentId = sourceDocumentId;
    }

    public UUID getDataVerifiedBy() {
        return dataVerifiedBy;
    }

    public Instant getDataVerifiedAt() {
        return dataVerifiedAt;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags == null ? List.of() : tags;
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

    /** Whether a person has checked the extracted values against the document. */
    public boolean isDataVerified() {
        return dataVerifiedAt != null && dataVerifiedBy != null;
    }

    public void markDataVerified(UUID userId) {
        this.dataVerifiedBy = userId;
        this.dataVerifiedAt = Instant.now();
    }
}
