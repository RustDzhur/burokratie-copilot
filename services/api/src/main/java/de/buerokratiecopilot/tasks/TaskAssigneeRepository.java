package de.buerokratiecopilot.tasks;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import de.buerokratiecopilot.tasks.domain.TaskAssignee;

public interface TaskAssigneeRepository extends JpaRepository<TaskAssignee, UUID> {

    List<TaskAssignee> findByTaskIdOrderByAssignedAtAsc(UUID taskId);

    Optional<TaskAssignee> findByTaskIdAndUserId(UUID taskId, UUID userId);

    List<TaskAssignee> findByTaskIdAndUserIdIsNotNull(UUID taskId);

    void deleteByTaskIdAndUserId(UUID taskId, UUID userId);
}
