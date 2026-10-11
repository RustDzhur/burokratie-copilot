package de.buerokratiecopilot.tasks;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import de.buerokratiecopilot.tasks.domain.TaskComment;

public interface TaskCommentRepository extends JpaRepository<TaskComment, UUID> {

    List<TaskComment> findByTaskIdAndDeletedAtIsNullOrderByCreatedAtAsc(UUID taskId);

    List<TaskComment> findByTaskIdAndInternalFalseAndDeletedAtIsNullOrderByCreatedAtAsc(UUID taskId);
}
