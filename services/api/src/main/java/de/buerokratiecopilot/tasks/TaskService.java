package de.buerokratiecopilot.tasks;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;
import de.buerokratiecopilot.tasks.domain.Task;
import de.buerokratiecopilot.tasks.domain.TaskAssignee;
import de.buerokratiecopilot.tasks.domain.TaskChecklistItem;
import de.buerokratiecopilot.tasks.domain.TaskComment;

/**
 * Task management (spec §12.1, §12.2).
 *
 * <p>Two rules carry most of the meaning here.</p>
 *
 * <p><strong>A task without an assignee is not nobody's task.</strong> It keeps
 * {@code assignmentPending} and appears in the manual distribution queue, ordered by due date. Work
 * that silently belongs to no one is work that is discovered too late, and the specification makes the
 * queue an explicit part of the model.</p>
 *
 * <p><strong>"Done" means the same thing to everybody.</strong> A task is only completed when its
 * completion criterion is stated and its required checklist items are ticked. Otherwise the status
 * would be an opinion, and the case history would record opinions as facts.</p>
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final TaskRepository tasks;
    private final TaskAssigneeRepository assignees;
    private final TaskChecklistItemRepository checklist;
    private final TaskCommentRepository comments;

    public TaskService(TaskRepository tasks, TaskAssigneeRepository assignees,
                       TaskChecklistItemRepository checklist, TaskCommentRepository comments) {
        this.tasks = tasks;
        this.assignees = assignees;
        this.checklist = checklist;
        this.comments = comments;
    }

    /** What is needed to create a task. */
    public record CreateRequest(UUID tenantId, UUID caseId, UUID parentTaskId, String title,
                                String description, Task.Priority priority, String prioritySource,
                                String priorityRationale, Instant dueAt, Instant remindAt,
                                String completionCriteria, boolean requiresApproval, UUID approverUserId,
                                UUID assigneeUserId, List<String> tags, Task.Source source,
                                UUID sourceDocumentId) {
    }

    /** Everything about a task that a client sees. */
    public record Detail(Task task, List<TaskAssignee> assignees, List<TaskChecklistItem> checklist,
                         List<TaskComment> comments, List<Task> subtasks) {
    }

    /**
     * Creates a task.
     *
     * <p>When an assignee is given the task is assigned immediately and leaves the distribution queue;
     * when none is given it stays pending, which is a state the platform reports rather than hides.</p>
     */
    @Transactional
    public Task create(CreateRequest request, UUID userId) {
        if (request.title() == null || request.title().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "A task needs a title");
        }

        Task task = new Task(UUID.randomUUID(), request.tenantId(), request.title().strip());
        task.setCaseId(request.caseId());
        task.setParentTaskId(request.parentTaskId());
        task.setDescription(request.description());
        task.setPriority(request.priority() == null ? Task.Priority.NORMAL : request.priority());
        task.setPrioritySource(request.prioritySource() == null ? "MANUAL" : request.prioritySource());
        task.setPriorityRationale(request.priorityRationale());
        task.setDueAt(request.dueAt());
        task.setRemindAt(request.remindAt());
        task.setCompletionCriteria(request.completionCriteria());
        task.setRequiresApproval(request.requiresApproval());
        task.setApproverUserId(request.approverUserId());
        task.setCreatedByUserId(userId);
        task.setTags(request.tags());
        task.setSource(request.source() == null ? Task.Source.MANUAL : request.source());
        task.setSourceDocumentId(request.sourceDocumentId());
        task.setAssignmentPending(true);

        Task saved = tasks.save(task);
        if (request.assigneeUserId() != null) {
            assign(saved, request.assigneeUserId(), TaskAssignee.Role.ASSIGNEE, userId);
        }
        log.info("Task {} created, assignment pending: {}", saved.getId(), saved.isAssignmentPending());
        return saved;
    }

    /** Hands a task to somebody, which is what takes it out of the manual distribution queue. */
    @Transactional
    public TaskAssignee assign(Task task, UUID userId, TaskAssignee.Role role, UUID assignedBy) {
        if (userId == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "An assignment needs a person");
        }
        TaskAssignee assignee = assignees.findByTaskIdAndUserId(task.getId(), userId)
                .orElseGet(() -> new TaskAssignee(UUID.randomUUID(), task.getTenantId(), task.getId(),
                        userId, role == null ? TaskAssignee.Role.ASSIGNEE : role));
        assignee.setRole(role == null ? TaskAssignee.Role.ASSIGNEE : role);
        assignee.setAssignedBy(assignedBy);
        TaskAssignee saved = assignees.save(assignee);

        if (saved.isDoer()) {
            // Only a doer takes the task out of the queue: a watcher does not do the work, so the
            // task would leave the distribution queue and still belong to nobody.
            task.setAssignmentPending(false);
            if (task.getStatus() == Task.Status.NEW) {
                task.setStatus(Task.Status.IN_PROGRESS);
                task.setStartedAt(Instant.now());
            }
            tasks.save(task);
        }
        return saved;
    }

    /** Removes somebody from a task; if no doer is left the task returns to the distribution queue. */
    @Transactional
    public void unassign(Task task, UUID userId) {
        assignees.deleteByTaskIdAndUserId(task.getId(), userId);
        boolean hasDoer = assignees.findByTaskIdOrderByAssignedAtAsc(task.getId()).stream()
                .anyMatch(TaskAssignee::isDoer);
        if (!hasDoer) {
            task.setAssignmentPending(true);
            tasks.save(task);
        }
    }

    /** The manual distribution queue: work nobody is responsible for, most urgent first. */
    @Transactional(readOnly = true)
    public List<Task> assignmentQueue() {
        return tasks.findAwaitingAssignment();
    }

    @Transactional(readOnly = true)
    public Page<Task> listVisible(UUID tenantId, UUID userId, boolean seeAll, Pageable pageable) {
        return listVisible(tenantId, userId, seeAll, null, null, pageable);
    }

    /**
     * The same list narrowed by status and priority. An absent filter means every value, passed explicitly:
     * an empty collection in an {@code in} clause matches nothing, which would turn "no filter" into "no tasks".
     */
    @Transactional(readOnly = true)
    public Page<Task> listVisible(UUID tenantId, UUID userId, boolean seeAll,
                                  java.util.Collection<Task.Status> statuses,
                                  java.util.Collection<Task.Priority> priorities, Pageable pageable) {
        java.util.Collection<Task.Status> wantedStatuses = statuses == null || statuses.isEmpty()
                ? java.util.EnumSet.allOf(Task.Status.class) : statuses;
        java.util.Collection<Task.Priority> wantedPriorities = priorities == null || priorities.isEmpty()
                ? java.util.EnumSet.allOf(Task.Priority.class) : priorities;
        return seeAll ? tasks.findAllInTenant(tenantId, wantedStatuses, wantedPriorities, pageable)
                : tasks.findVisibleTo(tenantId, userId, wantedStatuses, wantedPriorities, pageable);
    }

    @Transactional(readOnly = true)
    public Detail detail(UUID taskId) {
        Task task = tasks.findByIdAndDeletedAtIsNull(taskId)
                .orElseThrow(() -> ApiException.notFound("task"));
        return new Detail(task, assignees.findByTaskIdOrderByAssignedAtAsc(taskId),
                checklist.findByTaskIdOrderBySortOrderAsc(taskId),
                comments.findByTaskIdAndDeletedAtIsNullOrderByCreatedAtAsc(taskId),
                tasks.findByCaseIdAndDeletedAtIsNull(task.getParentTaskId() == null ? taskId
                        : task.getParentTaskId(), Pageable.unpaged()).stream()
                        .filter(candidate -> taskId.equals(candidate.getParentTaskId()))
                        .toList());
    }

    /**
     * Completes a task.
     *
     * <p>Refused when the completion criterion is missing, when a required checklist item is open, or
     * when the task needs an approval that has not been given. Each refusal names what is missing, so
     * the person can act on it rather than guess.</p>
     *
     * @param acknowledgeOpenItems true to complete despite open required items, with a reason — the
     *                             same escape hatch the case lifecycle offers, recorded the same way
     */
    @Transactional
    public Task complete(UUID taskId, UUID userId, String note, boolean acknowledgeOpenItems) {
        Task task = tasks.findByIdAndDeletedAtIsNull(taskId)
                .orElseThrow(() -> ApiException.notFound("task"));
        if (task.getStatus().isTerminal()) {
            throw ApiException.conflict("The task is already " + task.getStatus());
        }

        if (task.getCompletionCriteria() == null || task.getCompletionCriteria().isBlank()) {
            throw ApiException.conflict(
                    "This task has no completion criterion, so nobody can say what finished means. "
                            + "State what has to be true before completing it.");
        }

        long openRequired = checklist.countByTaskIdAndRequiredTrueAndDoneFalse(taskId);
        if (openRequired > 0) {
            if (!acknowledgeOpenItems) {
                throw ApiException.conflict("Required checklist items are still open: " + openRequired
                        + ". Tick them, or confirm the completion explicitly and say why.");
            }
            if (note == null || note.isBlank()) {
                throw new ApiException(ErrorCode.INVALID_REQUEST,
                        "Completing a task with open required items requires a note explaining why");
            }
        }

        if (task.isRequiresApproval() && task.getApprovedAt() == null) {
            throw ApiException.conflict(
                    "This task requires an approval that has not been given yet");
        }

        task.setStatus(Task.Status.DONE);
        task.setCompletedAt(Instant.now());
        task.setCompletedBy(userId);

        Map<String, Object> completion = new LinkedHashMap<>();
        completion.put("criteria", task.getCompletionCriteria());
        if (openRequired > 0) {
            completion.put("completedWithOpenItems", true);
            completion.put("openItems", openRequired);
            completion.put("note", note.strip());
        }
        // Metadata is replaced wholesale, so the completion record is merged into what is already
        // there rather than overwriting whatever another stage stored.
        Map<String, Object> merged = new java.util.HashMap<>(task.getMetadata());
        merged.put("completion", completion);
        task.setMetadata(Map.copyOf(merged));

        return tasks.save(task);
    }

    /** Approves a task that requires it, which is what unblocks its completion. */
    @Transactional
    public Task approve(UUID taskId, UUID userId) {
        Task task = tasks.findByIdAndDeletedAtIsNull(taskId)
                .orElseThrow(() -> ApiException.notFound("task"));
        if (!task.isRequiresApproval()) {
            throw ApiException.conflict("This task does not require an approval");
        }
        task.setApprovedBy(userId);
        task.setApprovedAt(Instant.now());
        return tasks.save(task);
    }

    /** Blocks a task; a block without a reason is refused by the schema and by this method alike. */
    @Transactional
    public Task block(UUID taskId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "A blocked task needs a reason; otherwise nobody can unblock it");
        }
        Task task = tasks.findByIdAndDeletedAtIsNull(taskId)
                .orElseThrow(() -> ApiException.notFound("task"));
        task.setStatus(Task.Status.BLOCKED);
        task.setBlockedReason(reason.strip());
        return tasks.save(task);
    }

    @Transactional
    public Task changeStatus(UUID taskId, Task.Status status, String reason) {
        Task task = tasks.findByIdAndDeletedAtIsNull(taskId)
                .orElseThrow(() -> ApiException.notFound("task"));
        if (status == Task.Status.DONE) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Use the completion endpoint: finishing a task means checking its criteria");
        }
        if (status == Task.Status.BLOCKED) {
            return block(taskId, reason);
        }
        if (status == Task.Status.CANCELLED) {
            if (reason == null || reason.isBlank()) {
                throw new ApiException(ErrorCode.INVALID_REQUEST,
                        "Cancelling a task requires a reason");
            }
            task.setCancelledAt(Instant.now());
            task.setCancelReason(reason.strip());
        }
        task.setStatus(status);
        return tasks.save(task);
    }

    /** Adds a checklist item; required items are what makes completion a fact rather than an opinion. */
    @Transactional
    public TaskChecklistItem addChecklistItem(UUID taskId, String label, boolean required) {
        if (label == null || label.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "A checklist item needs a label");
        }
        int order = checklist.findByTaskIdOrderBySortOrderAsc(taskId).size() + 1;
        return checklist.save(new TaskChecklistItem(UUID.randomUUID(), taskId, label.strip(), required,
                order));
    }

    @Transactional
    public TaskChecklistItem tickChecklistItem(UUID itemId, boolean done, UUID userId) {
        TaskChecklistItem item = checklist.findById(itemId)
                .orElseThrow(() -> ApiException.notFound("checklist item"));
        item.markDone(done, userId);
        return checklist.save(item);
    }

    @Transactional
    public TaskComment comment(UUID taskId, UUID tenantId, UUID authorId, String body, boolean internal) {
        if (body == null || body.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "A comment needs a body");
        }
        return comments.save(new TaskComment(UUID.randomUUID(), tenantId, taskId, authorId, body.strip(),
                internal));
    }

    @Transactional
    public void deleteComment(UUID commentId) {
        TaskComment comment = comments.findById(commentId)
                .orElseThrow(() -> ApiException.notFound("comment"));
        // Marked rather than removed: a comment somebody answered should not vanish from the thread.
        comment.delete();
        comments.save(comment);
    }

    /** Comments that may leave the tenant, for a letter or an export. */
    @Transactional(readOnly = true)
    public List<TaskComment> externalComments(UUID taskId) {
        return comments.findByTaskIdAndInternalFalseAndDeletedAtIsNullOrderByCreatedAtAsc(taskId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> statistics(UUID tenantId, UUID userId, boolean seeAll) {
        Page<Task> visible = listVisible(tenantId, userId, seeAll,
                org.springframework.data.domain.PageRequest.of(0, 500));
        Instant now = Instant.now();
        List<String> statuses = new ArrayList<>();
        Map<String, Object> statistics = new LinkedHashMap<>();
        long open = visible.stream().filter(task -> !task.getStatus().isTerminal()).count();
        long overdue = visible.stream().filter(task -> task.isOverdue(now)).count();
        long pending = visible.stream().filter(Task::isAssignmentPending).count();
        statistics.put("open", open);
        statistics.put("overdue", overdue);
        statistics.put("awaitingAssignment", pending);
        statistics.put("total", visible.getTotalElements());
        statistics.put("statuses", statuses);
        return statistics;
    }
}
