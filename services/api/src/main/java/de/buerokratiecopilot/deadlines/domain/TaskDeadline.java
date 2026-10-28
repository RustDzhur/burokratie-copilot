package de.buerokratiecopilot.deadlines.domain;

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
 * A date that matters, with where it came from (spec §13.2).
 *
 * <p>The type is the honest part. {@code CONFIRMED_LEGAL} means a person checked the rule and the date;
 * {@code ASSUMED_LEGAL} means the platform counted it from a rule nobody has verified yet;
 * {@code INTERNAL} is the tenant's own target, which has no legal weight at all. A single "deadline"
 * field would present all three as equally reliable.</p>
 */
@Entity
@Table(name = "task_deadlines")
public class TaskDeadline {

    public enum DeadlineType {
        DOCUMENT_DATE, INTERNAL, REMINDER, EXTERNAL_ORGANIZATION, ASSUMED_LEGAL, CONFIRMED_LEGAL
    }

    public enum Source {
        MANUAL, DOCUMENT, RULE, AI, WORKFLOW
    }

    @Id
    private UUID id;

    @Column(name = "task_id")
    private UUID taskId;

    @Column(name = "case_id")
    private UUID caseId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "deadline_type", nullable = false, length = 30)
    private DeadlineType deadlineType = DeadlineType.INTERNAL;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "time_zone", length = 60)
    private String timeZone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Source source = Source.MANUAL;

    @Column(name = "source_document_id")
    private UUID sourceDocumentId;

    @Column(name = "source_snippet")
    private String sourceSnippet;

    /** The rule the date was counted from, so the calculation can be reviewed later. */
    @Column(name = "rule_id")
    private UUID ruleId;

    @Column(name = "source_page")
    private Integer sourcePage;

    @Column(name = "rule_version")
    private Integer ruleVersion;

    /** True while the date rests on an unverified rule. */
    @Column(name = "requires_verification", nullable = false)
    private boolean requiresVerification = true;

    @Column(name = "calculated_at")
    private Instant calculatedAt;

    @Column(name = "confirmed_by")
    private UUID confirmedBy;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    private String note;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected TaskDeadline() {
        // for JPA
    }

    public TaskDeadline(UUID id, UUID tenantId, UUID taskId, UUID caseId, DeadlineType deadlineType) {
        this.id = id;
        this.tenantId = tenantId;
        this.taskId = taskId;
        this.caseId = caseId;
        this.deadlineType = deadlineType;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public DeadlineType getDeadlineType() {
        return deadlineType;
    }

    public void setDeadlineType(DeadlineType deadlineType) {
        this.deadlineType = deadlineType;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public void setDueAt(Instant dueAt) {
        this.dueAt = dueAt;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }

    public Source getSource() {
        return source;
    }

    public void setSource(Source source) {
        this.source = source;
    }

    public Integer getSourcePage() {
        return sourcePage;
    }

    public void setSourcePage(Integer sourcePage) {
        this.sourcePage = sourcePage;
    }

    public Integer getRuleVersion() {
        return ruleVersion;
    }

    public void setRuleVersion(Integer ruleVersion) {
        this.ruleVersion = ruleVersion;
    }

    public UUID getSourceDocumentId() {
        return sourceDocumentId;
    }

    public void setSourceDocumentId(UUID sourceDocumentId) {
        this.sourceDocumentId = sourceDocumentId;
    }

    public String getSourceSnippet() {
        return sourceSnippet;
    }

    public void setSourceSnippet(String sourceSnippet) {
        this.sourceSnippet = sourceSnippet;
    }

    public UUID getRuleId() {
        return ruleId;
    }

    public void setRuleId(UUID ruleId) {
        this.ruleId = ruleId;
    }

    public boolean isRequiresVerification() {
        return requiresVerification;
    }

    public Instant getCalculatedAt() {
        return calculatedAt;
    }

    public void setCalculatedAt(Instant calculatedAt) {
        this.calculatedAt = calculatedAt;
    }

    public UUID getConfirmedBy() {
        return confirmedBy;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
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

    /** A person confirms the date itself, which is what turns an assumption into a fact. */
    public void confirm(UUID userId, String note) {
        this.confirmedBy = userId;
        this.confirmedAt = Instant.now();
        this.requiresVerification = false;
        if (this.deadlineType == DeadlineType.ASSUMED_LEGAL) {
            this.deadlineType = DeadlineType.CONFIRMED_LEGAL;
        }
        if (note != null && !note.isBlank()) {
            this.note = note.strip();
        }
    }
}
