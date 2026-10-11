package de.buerokratiecopilot.tasks.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Who works on a task, and in which capacity (spec §12.1).
 *
 * <p>A task can have several people on it with different jobs — one does the work, another approves it,
 * a third only wants to know. Collapsing them into a single "assignee" column would lose exactly the
 * distinction that decides who may close the task.</p>
 */
@Entity
@Table(name = "task_assignees")
public class TaskAssignee {

    public enum Role {
        ASSIGNEE, APPROVER, WATCHER, REVIEWER
    }

    @Id
    private UUID id;

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role = Role.ASSIGNEE;

    @Column(name = "assigned_by")
    private UUID assignedBy;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    protected TaskAssignee() {
        // for JPA
    }

    public TaskAssignee(UUID id, UUID tenantId, UUID taskId, UUID userId, Role role) {
        this.id = id;
        this.tenantId = tenantId;
        this.taskId = taskId;
        this.userId = userId;
        this.role = role;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getUserId() {
        return userId;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public UUID getAssignedBy() {
        return assignedBy;
    }

    public void setAssignedBy(UUID assignedBy) {
        this.assignedBy = assignedBy;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public boolean isDoer() {
        return role == Role.ASSIGNEE;
    }
}
