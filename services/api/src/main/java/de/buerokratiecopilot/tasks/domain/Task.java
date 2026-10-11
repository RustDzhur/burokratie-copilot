package de.buerokratiecopilot.tasks.domain;

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
 * A piece of work (spec §12.1).
 *
 * <p>A task differs from a reminder in three ways the schema already encodes, and all three matter:
 * it has a <strong>completion criterion</strong> (what "done" means, in words, before anybody starts),
 * it has <strong>assignees</strong> rather than one owner, and a task without an assignee is not
 * silently nobody's — it carries {@code assignment_pending} and waits in a queue for a person to
 * distribute it.</p>
 *
 * <p>Evidence is a separate concern: what proves the work was done hangs off the task through
 * {@code task_evidence} and is added by the stages that produce it (documents, correspondence,
 * payments), so this entity does not pretend to hold it.</p>
 */
@Entity
@Table(name = "tasks")
public class Task {

    /** Work states; {@code DONE} and {@code CANCELLED} are the only terminal ones. */
    public enum Status {
        NEW, IN_PROGRESS, WAITING, BLOCKED, IN_REVIEW, DONE, CANCELLED;

        public boolean isTerminal() {
            return this == DONE || this == CANCELLED;
        }
    }

    public enum Priority {
        CRITICAL, HIGH, NORMAL, LOW, INFORMATIONAL
    }

    /** Where a task came from, which decides whether a human may reorder it freely. */
    public enum Source {
        MANUAL, DOCUMENT, WORKFLOW, AI_SUGGESTION, DEADLINE, IMPORT
    }

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "workspace_id")
    private UUID workspaceId;

    @Column(name = "case_id")
    private UUID caseId;

    /** Subtasks hang off their parent; the parent's status is not derived from them automatically. */
    @Column(name = "parent_task_id")
    private UUID parentTaskId;

    @Column(nullable = false, length = 300)
    private String title;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Status status = Status.NEW;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Priority priority = Priority.NORMAL;

    @Column(name = "priority_source", nullable = false, length = 20)
    private String prioritySource = "MANUAL";

    @Column(name = "priority_rationale")
    private String priorityRationale;

    @Column(name = "created_by_user_id")
    private UUID createdByUserId;

    @Column(name = "approver_user_id")
    private UUID approverUserId;

    /** True while nobody is responsible, which is what the distribution queue lists. */
    @Column(name = "assignment_pending", nullable = false)
    private boolean assignmentPending = true;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "remind_at")
    private Instant remindAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "completed_by")
    private UUID completedBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Source source = Source.MANUAL;

    @Column(name = "source_document_id")
    private UUID sourceDocumentId;

    @Column(name = "workflow_run_id")
    private UUID workflowRunId;

    /** In words, before the work starts: what has to be true for this task to be finished. */
    @Column(name = "completion_criteria")
    private String completionCriteria;

    @Column(name = "requires_approval", nullable = false)
    private boolean requiresApproval;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    /** Required by the schema whenever the status is BLOCKED: a block without a reason is a mystery. */
    @Column(name = "blocked_reason")
    private String blockedReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<String> tags = List.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> metadata = Map.of();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Task() {
        // for JPA
    }

    public Task(UUID id, UUID tenantId, String title) {
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

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(UUID workspaceId) {
        this.workspaceId = workspaceId;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public void setCaseId(UUID caseId) {
        this.caseId = caseId;
    }

    public UUID getParentTaskId() {
        return parentTaskId;
    }

    public void setParentTaskId(UUID parentTaskId) {
        this.parentTaskId = parentTaskId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Priority getPriority() {
        return priority;
    }

    public void setPriority(Priority priority) {
        this.priority = priority;
    }

    public String getPrioritySource() {
        return prioritySource;
    }

    public void setPrioritySource(String prioritySource) {
        this.prioritySource = prioritySource;
    }

    public String getPriorityRationale() {
        return priorityRationale;
    }

    public void setPriorityRationale(String priorityRationale) {
        this.priorityRationale = priorityRationale;
    }

    public UUID getCreatedByUserId() {
        return createdByUserId;
    }

    public void setCreatedByUserId(UUID createdByUserId) {
        this.createdByUserId = createdByUserId;
    }

    public UUID getApproverUserId() {
        return approverUserId;
    }

    public void setApproverUserId(UUID approverUserId) {
        this.approverUserId = approverUserId;
    }

    public boolean isAssignmentPending() {
        return assignmentPending;
    }

    public void setAssignmentPending(boolean assignmentPending) {
        this.assignmentPending = assignmentPending;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public void setDueAt(Instant dueAt) {
        this.dueAt = dueAt;
    }

    public Instant getRemindAt() {
        return remindAt;
    }

    public void setRemindAt(Instant remindAt) {
        this.remindAt = remindAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public UUID getCompletedBy() {
        return completedBy;
    }

    public void setCompletedBy(UUID completedBy) {
        this.completedBy = completedBy;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public void setCancelledAt(Instant cancelledAt) {
        this.cancelledAt = cancelledAt;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public void setCancelReason(String cancelReason) {
        this.cancelReason = cancelReason;
    }

    public Source getSource() {
        return source;
    }

    public void setSource(Source source) {
        this.source = source;
    }

    public UUID getSourceDocumentId() {
        return sourceDocumentId;
    }

    public void setSourceDocumentId(UUID sourceDocumentId) {
        this.sourceDocumentId = sourceDocumentId;
    }

    public String getCompletionCriteria() {
        return completionCriteria;
    }

    public void setCompletionCriteria(String completionCriteria) {
        this.completionCriteria = completionCriteria;
    }

    public boolean isRequiresApproval() {
        return requiresApproval;
    }

    public void setRequiresApproval(boolean requiresApproval) {
        this.requiresApproval = requiresApproval;
    }

    public UUID getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(UUID approvedBy) {
        this.approvedBy = approvedBy;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(Instant approvedAt) {
        this.approvedAt = approvedAt;
    }

    public String getBlockedReason() {
        return blockedReason;
    }

    public void setBlockedReason(String blockedReason) {
        this.blockedReason = blockedReason;
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

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata == null ? Map.of() : metadata;
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

    public boolean isOverdue(Instant now) {
        return !status.isTerminal() && dueAt != null && dueAt.isBefore(now);
    }
}
