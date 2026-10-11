package de.buerokratiecopilot.tasks.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A comment on a task (spec §12.1).
 *
 * <p>{@code internal} separates what the team writes for itself from what may travel to a counterparty
 * or into a letter. A single comment stream would make that decision implicit, and the wrong guess
 * sends an internal note to a customer.</p>
 */
@Entity
@Table(name = "task_comments")
public class TaskComment {

    @Id
    private UUID id;

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "author_user_id")
    private UUID authorUserId;

    @Column(nullable = false)
    private String body;

    @Column(nullable = false)
    private boolean internal = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected TaskComment() {
        // for JPA
    }

    public TaskComment(UUID id, UUID tenantId, UUID taskId, UUID authorUserId, String body,
                       boolean internal) {
        this.id = id;
        this.tenantId = tenantId;
        this.taskId = taskId;
        this.authorUserId = authorUserId;
        this.body = body;
        this.internal = internal;
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

    public UUID getAuthorUserId() {
        return authorUserId;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
        this.updatedAt = Instant.now();
    }

    public boolean isInternal() {
        return internal;
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

    public void delete() {
        this.deletedAt = Instant.now();
    }
}
