package de.buerokratiecopilot.deadlines.domain;

import java.time.Instant;
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

import de.buerokratiecopilot.deadlines.DeadlineCalculator;

/**
 * A rule that says how a deadline is counted (spec §13.2).
 *
 * <p>The register ships <strong>empty</strong>. This is a deliberate decision and the most important
 * one in this part of the platform: a deadline is a legal statement, and a platform that ships with
 * invented periods would produce dates that look authoritative and are not. Whoever operates the
 * platform enters the rules that apply to them, from their own legal source, and verifies them.</p>
 *
 * <p>Until a rule is verified, {@code requiresVerification} stays true and any deadline computed from
 * it is recorded as {@code ASSUMED_LEGAL} rather than {@code CONFIRMED_LEGAL}. The distinction is the
 * schema's, not an invention of this class, and it is what lets a user see which dates they may rely
 * on.</p>
 */
@Entity
@Table(name = "deadline_rules")
public class DeadlineRule {

    public enum PeriodUnit {
        DAYS, WEEKS, MONTHS, YEARS
    }

    public enum CountingMode {
        CALENDAR_DAYS, WORKING_DAYS
    }

    /** What starts the period; the schema fixes these values. */
    public enum StartEvent {
        DOCUMENT_DATE, RECEIPT_DATE, SERVICE_DATE, CASE_EVENT, CONTRACT_EVENT
    }

    public enum DeliveryMethod {
        POST, EMAIL, PERSONAL, COURIER, UNKNOWN
    }

    @Id
    private UUID id;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(nullable = false, length = 80)
    private String code;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> name;

    private String description;

    /** Where the rule comes from, in the operator's own words: a paragraph, a contract clause, a note. */
    @Column(name = "legal_basis")
    private String legalBasis;

    @Column(name = "document_type_id")
    private UUID documentTypeId;

    @Column(name = "case_type_id")
    private UUID caseTypeId;

    @Column(name = "period_value")
    private Integer periodValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "period_unit", length = 20)
    private PeriodUnit periodUnit;

    @Enumerated(EnumType.STRING)
    @Column(name = "counting_mode", nullable = false, length = 20)
    private CountingMode countingMode = CountingMode.CALENDAR_DAYS;

    @Enumerated(EnumType.STRING)
    @Column(name = "start_event", nullable = false, length = 30)
    private StartEvent startEvent = StartEvent.RECEIPT_DATE;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_method", length = 20)
    private DeliveryMethod deliveryMethod;

    @Column(name = "holiday_calendar_id")
    private UUID holidayCalendarId;

    @Column(name = "requires_verification", nullable = false)
    private boolean requiresVerification = true;

    @Column(name = "verified_by")
    private UUID verifiedBy;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(nullable = false)
    private int version = 1;

    /** Required by the schema: a rule applies from a moment, and a rule without one is ambiguous. */
    @Column(name = "valid_from", nullable = false)
    private Instant validFrom = Instant.now();

    @Column(name = "valid_to")
    private Instant validTo;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected DeadlineRule() {
        // for JPA
    }

    public DeadlineRule(UUID id, UUID tenantId, String code, Map<String, Object> name) {
        this.id = id;
        this.tenantId = tenantId;
        this.code = code;
        this.name = name;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getCode() {
        return code;
    }

    public Map<String, Object> getName() {
        return name;
    }

    public String nameIn(String language) {
        if (name == null) {
            return code;
        }
        Object localized = name.get(language);
        if (localized == null) {
            localized = name.get("de");
        }
        return localized == null ? code : String.valueOf(localized);
    }

    public void setName(Map<String, Object> name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getLegalBasis() {
        return legalBasis;
    }

    public void setLegalBasis(String legalBasis) {
        this.legalBasis = legalBasis;
    }

    public UUID getDocumentTypeId() {
        return documentTypeId;
    }

    public void setDocumentTypeId(UUID documentTypeId) {
        this.documentTypeId = documentTypeId;
    }

    public UUID getCaseTypeId() {
        return caseTypeId;
    }

    public void setCaseTypeId(UUID caseTypeId) {
        this.caseTypeId = caseTypeId;
    }

    public Integer getPeriodValue() {
        return periodValue;
    }

    public void setPeriod(Integer periodValue, PeriodUnit periodUnit) {
        this.periodValue = periodValue;
        this.periodUnit = periodUnit;
    }

    public PeriodUnit getPeriodUnit() {
        return periodUnit;
    }

    public CountingMode getCountingMode() {
        return countingMode;
    }

    public void setCountingMode(CountingMode countingMode) {
        this.countingMode = countingMode;
    }

    public StartEvent getStartEvent() {
        return startEvent;
    }

    public void setStartEvent(StartEvent startEvent) {
        this.startEvent = startEvent;
    }

    public DeliveryMethod getDeliveryMethod() {
        return deliveryMethod;
    }

    public void setDeliveryMethod(DeliveryMethod deliveryMethod) {
        this.deliveryMethod = deliveryMethod;
    }

    public UUID getHolidayCalendarId() {
        return holidayCalendarId;
    }

    public void setHolidayCalendarId(UUID holidayCalendarId) {
        this.holidayCalendarId = holidayCalendarId;
    }

    public boolean isRequiresVerification() {
        return requiresVerification;
    }

    public UUID getVerifiedBy() {
        return verifiedBy;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public int getVersion() {
        return version;
    }

    public Instant getValidFrom() {
        return validFrom;
    }

    public void setValidFrom(Instant validFrom) {
        this.validFrom = validFrom;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
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

    public boolean isVerified() {
        return verifiedAt != null && verifiedBy != null;
    }

    /** Records that a responsible person checked this rule against their source. */
    public void verify(UUID userId) {
        if (periodValue == null || periodUnit == null) {
            throw new IllegalStateException(
                    "A rule without a period cannot be verified; there is nothing to confirm");
        }
        this.verifiedBy = userId;
        this.verifiedAt = Instant.now();
        this.requiresVerification = false;
    }

    /** The calculation this rule describes, ready for the calculator. */
    public DeadlineCalculator.CountingMode calculatorMode() {
        return countingMode == CountingMode.WORKING_DAYS
                ? DeadlineCalculator.CountingMode.WORKING_DAYS
                : DeadlineCalculator.CountingMode.CALENDAR_DAYS;
    }

    public DeadlineCalculator.PeriodUnit calculatorUnit() {
        return DeadlineCalculator.PeriodUnit.valueOf(periodUnit.name());
    }
}
