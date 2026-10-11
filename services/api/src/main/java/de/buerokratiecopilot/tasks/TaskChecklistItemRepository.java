package de.buerokratiecopilot.tasks;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import de.buerokratiecopilot.tasks.domain.TaskChecklistItem;

public interface TaskChecklistItemRepository extends JpaRepository<TaskChecklistItem, UUID> {

    List<TaskChecklistItem> findByTaskIdOrderBySortOrderAsc(UUID taskId);

    long countByTaskIdAndRequiredTrueAndDoneFalse(UUID taskId);
}
