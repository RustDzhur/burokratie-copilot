package de.buerokratiecopilot.deadlines;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import de.buerokratiecopilot.deadlines.domain.TaskDeadline;

public interface TaskDeadlineRepository extends JpaRepository<TaskDeadline, UUID> {

    /**
     * Deadlines of a case: those of its tasks.
     *
     * <p>A deadline always belongs to a task, so a case's dates are collected through its tasks rather
     * than stored twice. The case column is kept in step for queries that already have it, but the
     * join is the source of truth.</p>
     */
    @Query("""
            select d from TaskDeadline d, Task t
            where d.taskId = t.id and t.caseId = :caseId and d.active = true
            order by d.dueDate asc
            """)
    List<TaskDeadline> findForCase(@Param("caseId") UUID caseId);

    List<TaskDeadline> findByCaseIdAndActiveTrueOrderByDueDateAsc(UUID caseId);

    List<TaskDeadline> findByTaskIdAndActiveTrueOrderByDueDateAsc(UUID taskId);

    List<TaskDeadline> findByTenantIdAndRequiresVerificationTrueAndActiveTrue(UUID tenantId);
}
