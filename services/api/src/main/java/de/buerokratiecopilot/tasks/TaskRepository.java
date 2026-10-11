package de.buerokratiecopilot.tasks;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import de.buerokratiecopilot.tasks.domain.Task;

public interface TaskRepository extends JpaRepository<Task, UUID> {

    Optional<Task> findByIdAndDeletedAtIsNull(UUID id);

    Page<Task> findByCaseIdAndDeletedAtIsNull(UUID caseId, Pageable pageable);

    /**
     * The manual distribution queue: tasks nobody is responsible for.
     *
     * <p>Ordered by due date first, because that is the order in which leaving them unassigned starts
     * to hurt. A task without a due date comes last.</p>
     */
    @Query("""
            select t from Task t
            where t.deletedAt is null and t.assignmentPending = true
              and t.status not in (de.buerokratiecopilot.tasks.domain.Task$Status.DONE,
                                   de.buerokratiecopilot.tasks.domain.Task$Status.CANCELLED)
            order by t.dueAt asc nulls last, t.createdAt asc
            """)
    List<Task> findAwaitingAssignment();

    /**
     * Tasks a user is involved in: as doer, approver, watcher or reviewer.
     *
     * <p>Like cases, a task is visible to the people working on it rather than to the whole tenant.</p>
     */
    @Query("""
            select t from Task t
            where t.deletedAt is null and t.tenantId = :tenantId
              and (t.createdByUserId = :userId or t.approverUserId = :userId
                   or exists (select 1 from TaskAssignee a
                              where a.taskId = t.id and a.userId = :userId))
            """)
    Page<Task> findVisibleTo(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId,
                             Pageable pageable);

    @Query("""
            select t from Task t
            where t.deletedAt is null and t.tenantId = :tenantId
            """)
    Page<Task> findAllInTenant(@Param("tenantId") UUID tenantId, Pageable pageable);

    long countByCaseIdAndStatusNotIn(UUID caseId, List<Task.Status> statuses);

    long countByAssignmentPendingTrueAndStatusNotIn(List<Task.Status> statuses);
}
