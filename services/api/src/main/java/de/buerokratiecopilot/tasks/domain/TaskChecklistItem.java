package de.buerokratiecopilot.tasks.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One step of a task's checklist (spec §12.1).
 *
 * <p>{@code isRequired} is what makes a checklist more than a note: a required item that is not done
 * stops the task from being completed, so "done" means the same thing to everybody who reads it.</p>
 */
@Entity
@Table(name = "task_checklist_items")
public class TaskChecklistItem {

    @Id
    private UUID id;

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    @Column(nullable = false, length = 300)
    private String label;

    @Column(name = "is_required", nullable = false)
    private boolean required;

    @Column(name = "is_done", nullable = false)
    private boolean done;

    @Column(name = "done_by")
    private UUID doneBy;

    @Column(name = "done_at")
    private Instant doneAt;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 100;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected TaskChecklistItem() {
        // for JPA
    }

    public TaskChecklistItem(UUID id, UUID taskId, String label, boolean required, int sortOrder) {
        this.id = id;
        this.taskId = taskId;
        this.label = label;
        this.required = required;
        this.sortOrder = sortOrder;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }

    public boolean isDone() {
        return done;
    }

    public UUID getDoneBy() {
        return doneBy;
    }

    public Instant getDoneAt() {
        return doneAt;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Ticking and unticking are both allowed: a mistake must be correctable. */
    public void markDone(boolean done, UUID userId) {
        this.done = done;
        this.doneBy = done ? userId : null;
        this.doneAt = done ? Instant.now() : null;
    }
}
