package de.buerokratiecopilot.shared.outbox;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    long countByStatus(OutboxEvent.Status status);

    List<OutboxEvent> findByStatus(OutboxEvent.Status status);
}
