package de.buerokratiecopilot.tasks.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.tasks.TaskService;
import de.buerokratiecopilot.tasks.domain.Task;
import de.buerokratiecopilot.tasks.domain.TaskAssignee;
import de.buerokratiecopilot.tasks.domain.TaskChecklistItem;
import de.buerokratiecopilot.tasks.domain.TaskComment;

/**
 * Task API (spec §12, §20).
 *
 * <p>The distribution queue is a first-class endpoint rather than a filter a client has to know: a task
 * nobody is responsible for is a state the platform reports, and the interface has to be able to show
 * it. Completion and approval are separate endpoints because they are separate decisions made by
 * different people.</p>
 */
@RestController
@RequestMapping("/api/v1/tasks")
@Tag(name = "Tasks", description = "Work items, their assignees, checklists and comments")
public class TaskController {

    private final TaskService tasks;
    private final de.buerokratiecopilot.identity.UserRepository users;
    private final de.buerokratiecopilot.cases.CaseRepository caseRepository;

    public TaskController(TaskService tasks, de.buerokratiecopilot.identity.UserRepository users,
                          de.buerokratiecopilot.cases.CaseRepository caseRepository) {
        this.tasks = tasks;
        this.users = users;
        this.caseRepository = caseRepository;
    }

    /** A list row with the names a person reads: who does it and which case it belongs to. */
    private TaskResponse listRow(Task task) {
        TaskService.Detail detail = tasks.detail(task.getId());
        long openRequired = detail.checklist().stream()
                .filter(item -> item.isRequired() && !item.isDone()).count();
        List<String> names = detail.assignees().stream()
                .map(assignee -> users.findById(assignee.getUserId())
                        .map(user -> user.getDisplayName() != null ? user.getDisplayName() : user.getEmail())
                        .orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
        String reference = task.getCaseId() == null ? null
                : caseRepository.findById(task.getCaseId())
                        .map(de.buerokratiecopilot.cases.domain.Case::getReference).orElse(null);
        return TaskResponse.from(task, openRequired).withContext(names, reference);
    }

    public record CreateTaskRequest(String caseId, String parentTaskId, String title, String description,
                                    String priority, String dueAt, String remindAt,
                                    String completionCriteria, boolean requiresApproval,
                                    String approverUserId, String assigneeUserId, List<String> tags) {
    }

    public record AssigneeRequest(String userId, String role) {
    }

    public record ChecklistItemRequest(String label, boolean required) {
    }

    public record CommentRequest(String body, boolean internal) {
    }

    public record CompleteRequest(String note, boolean acknowledgeOpenItems) {
    }

    public record StatusRequest(String status, String reason) {
    }

    public record AssigneeResponse(String id, String userId, String role, String assignedAt,
                                   String completedAt) {

        static AssigneeResponse from(TaskAssignee assignee) {
            return new AssigneeResponse(assignee.getId().toString(), assignee.getUserId().toString(),
                    assignee.getRole().name(), String.valueOf(assignee.getAssignedAt()),
                    assignee.getCompletedAt() == null ? null : assignee.getCompletedAt().toString());
        }
    }

    public record ChecklistResponse(String id, String label, boolean required, boolean done,
                                    int sortOrder) {

        static ChecklistResponse from(TaskChecklistItem item) {
            return new ChecklistResponse(item.getId().toString(), item.getLabel(), item.isRequired(),
                    item.isDone(), item.getSortOrder());
        }
    }

    public record CommentResponse(String id, String authorUserId, String body, boolean internal,
                                  String createdAt) {

        static CommentResponse from(TaskComment comment) {
            return new CommentResponse(comment.getId().toString(),
                    comment.getAuthorUserId() == null ? null : comment.getAuthorUserId().toString(),
                    comment.getBody(), comment.isInternal(), String.valueOf(comment.getCreatedAt()));
        }
    }

    public record TaskResponse(String id, String caseId, String parentTaskId, String title,
                               String description, String status, String priority,
                               String prioritySource, String priorityRationale,
                               boolean assignmentPending, String dueAt, String remindAt,
                               String startedAt, String completionCriteria, boolean requiresApproval,
                               String approvedAt, String blockedReason, String completedAt,
                               List<String> tags, long openRequiredItems, List<String> assigneeNames,
                               String caseReference) {

        static TaskResponse from(Task task, long openRequiredItems) {
            return new TaskResponse(task.getId().toString(),
                    task.getCaseId() == null ? null : task.getCaseId().toString(),
                    task.getParentTaskId() == null ? null : task.getParentTaskId().toString(),
                    task.getTitle(), task.getDescription(), task.getStatus().name(),
                    task.getPriority().name(), task.getPrioritySource(), task.getPriorityRationale(),
                    task.isAssignmentPending(), instant(task.getDueAt()), instant(task.getRemindAt()),
                    instant(task.getStartedAt()), task.getCompletionCriteria(),
                    task.isRequiresApproval(), instant(task.getApprovedAt()),
                    task.getBlockedReason(), instant(task.getCompletedAt()), task.getTags(),
                    openRequiredItems, List.of(), null);
        }

        TaskResponse withContext(List<String> assigneeNames, String caseReference) {
            return new TaskResponse(id, caseId, parentTaskId, title, description, status, priority,
                    prioritySource, priorityRationale, assignmentPending, dueAt, remindAt, startedAt,
                    completionCriteria, requiresApproval, approvedAt, blockedReason, completedAt, tags,
                    openRequiredItems, assigneeNames, caseReference);
        }

        private static String instant(Instant value) {
            return value == null ? null : value.toString();
        }
    }

    public record TaskDetailResponse(TaskResponse task, List<AssigneeResponse> assignees,
                                     List<ChecklistResponse> checklist, List<CommentResponse> comments,
                                     List<TaskResponse> subtasks) {
    }

    @PostMapping
    @PreAuthorize("hasAuthority('tasks.create')")
    @Operation(summary = "Create a task; without an assignee it enters the distribution queue")
    public ResponseEntity<TaskResponse> create(@RequestBody CreateTaskRequest request) {
        UUID userId = CurrentUser.required();
        Task created = tasks.create(new TaskService.CreateRequest(
                RequestContext.tenantId().orElseThrow(),
                uuid(request.caseId()), uuid(request.parentTaskId()), request.title(),
                request.description(),
                request.priority() == null ? null : Task.Priority.valueOf(request.priority()),
                "MANUAL", null,
                instant(request.dueAt()), instant(request.remindAt()), request.completionCriteria(),
                request.requiresApproval(), uuid(request.approverUserId()), uuid(request.assigneeUserId()),
                request.tags(), Task.Source.MANUAL, null), userId);

        return ResponseEntity.status(HttpStatus.CREATED).body(TaskResponse.from(created, 0));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('tasks.view')")
    @Operation(summary = "Tasks the caller is involved in")
    public ResponseEntity<Page<TaskResponse>> list(
            @RequestParam(required = false) List<Task.Status> status,
            @RequestParam(required = false) List<Task.Priority> priority,
            @PageableDefault(size = 25, sort = "createdAt", direction = org.springframework.data.domain.Sort.Direction.DESC) Pageable pageable) {
        UUID userId = CurrentUser.required();
        UUID tenantId = RequestContext.tenantId().orElseThrow();
        return ResponseEntity.ok(tasks.listVisible(tenantId, userId, hasPermission("tasks.assign"), status, priority, pageable)
                .map(this::listRow));
    }

    @GetMapping("/assignment-queue")
    @PreAuthorize("hasAuthority('tasks.assign')")
    @Operation(summary = "Tasks nobody is responsible for, most urgent first")
    public ResponseEntity<List<TaskResponse>> assignmentQueue() {
        return ResponseEntity.ok(tasks.assignmentQueue().stream()
                .map(task -> TaskResponse.from(task, 0)).toList());
    }

    @GetMapping("/statistics")
    @PreAuthorize("hasAuthority('tasks.view')")
    @Operation(summary = "Open, overdue and unassigned tasks")
    public ResponseEntity<Map<String, Object>> statistics() {
        UUID userId = CurrentUser.required();
        UUID tenantId = RequestContext.tenantId().orElseThrow();
        return ResponseEntity.ok(tasks.statistics(tenantId, userId, hasPermission("tasks.assign")));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('tasks.view')")
    @Operation(summary = "The task with its assignees, checklist and comments")
    public ResponseEntity<TaskDetailResponse> detail(@PathVariable UUID id) {
        TaskService.Detail detail = tasks.detail(id);
        long openRequired = detail.checklist().stream()
                .filter(item -> item.isRequired() && !item.isDone()).count();
        return ResponseEntity.ok(new TaskDetailResponse(
                TaskResponse.from(detail.task(), openRequired),
                detail.assignees().stream().map(AssigneeResponse::from).toList(),
                detail.checklist().stream().map(ChecklistResponse::from).toList(),
                detail.comments().stream().map(CommentResponse::from).toList(),
                detail.subtasks().stream().map(subtask -> TaskResponse.from(subtask, 0)).toList()));
    }

    @PostMapping("/{id}/assignees")
    @PreAuthorize("hasAuthority('tasks.assign')")
    @Operation(summary = "Hand the task to somebody, which takes it out of the distribution queue")
    public ResponseEntity<AssigneeResponse> assign(@PathVariable UUID id,
                                                   @RequestBody AssigneeRequest request) {
        Task task = tasks.detail(id).task();
        TaskAssignee assignee = tasks.assign(task, uuid(request.userId()),
                request.role() == null ? null : TaskAssignee.Role.valueOf(request.role()),
                CurrentUser.required());
        return ResponseEntity.status(HttpStatus.CREATED).body(AssigneeResponse.from(assignee));
    }

    @DeleteMapping("/{id}/assignees/{userId}")
    @PreAuthorize("hasAuthority('tasks.assign')")
    @Operation(summary = "Remove somebody from the task; without a doer it returns to the queue")
    public ResponseEntity<Void> unassign(@PathVariable UUID id, @PathVariable UUID userId) {
        tasks.unassign(tasks.detail(id).task(), userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/checklist")
    @PreAuthorize("hasAuthority('tasks.edit')")
    @Operation(summary = "Add a checklist item")
    public ResponseEntity<ChecklistResponse> addChecklistItem(@PathVariable UUID id,
                                                              @RequestBody ChecklistItemRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ChecklistResponse.from(
                tasks.addChecklistItem(id, request.label(), request.required())));
    }

    @PostMapping("/checklist/{itemId}/tick")
    @PreAuthorize("hasAuthority('tasks.edit')")
    @Operation(summary = "Tick or untick a checklist item")
    public ResponseEntity<ChecklistResponse> tick(@PathVariable UUID itemId,
                                                  @RequestParam boolean done) {
        return ResponseEntity.ok(ChecklistResponse.from(
                tasks.tickChecklistItem(itemId, done, CurrentUser.required())));
    }

    @PostMapping("/{id}/comments")
    @PreAuthorize("hasAuthority('tasks.edit')")
    @Operation(summary = "Comment on a task; internal comments never leave the tenant")
    public ResponseEntity<CommentResponse> comment(@PathVariable UUID id,
                                                   @RequestBody CommentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(CommentResponse.from(
                tasks.comment(id, RequestContext.tenantId().orElseThrow(), CurrentUser.required(),
                        request.body(), request.internal())));
    }

    @DeleteMapping("/comments/{commentId}")
    @PreAuthorize("hasAuthority('tasks.edit')")
    @Operation(summary = "Remove a comment; it is marked deleted rather than dropped from the thread")
    public ResponseEntity<Void> deleteComment(@PathVariable UUID commentId) {
        tasks.deleteComment(commentId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasAuthority('tasks.complete')")
    @Operation(summary = "Complete a task; its criteria and required items have to hold")
    public ResponseEntity<TaskResponse> complete(@PathVariable UUID id,
                                                 @RequestBody(required = false) CompleteRequest request) {
        Task completed = tasks.complete(id, CurrentUser.required(),
                request == null ? null : request.note(),
                request != null && request.acknowledgeOpenItems());
        return ResponseEntity.ok(TaskResponse.from(completed, 0));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('tasks.approve')")
    @Operation(summary = "Approve a task that requires it")
    public ResponseEntity<TaskResponse> approve(@PathVariable UUID id) {
        return ResponseEntity.ok(TaskResponse.from(tasks.approve(id, CurrentUser.required()), 0));
    }

    @PostMapping("/{id}/status")
    @PreAuthorize("hasAuthority('tasks.edit')")
    @Operation(summary = "Change a task's status for everything except completion")
    public ResponseEntity<TaskResponse> changeStatus(@PathVariable UUID id,
                                                     @RequestBody StatusRequest request) {
        return ResponseEntity.ok(TaskResponse.from(tasks.changeStatus(id,
                Task.Status.valueOf(request.status()), request.reason()), 0));
    }

    private static boolean hasPermission(String permission) {
        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> permission.equals(authority.getAuthority()));
    }

    private static UUID uuid(String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    private static Instant instant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }
}
