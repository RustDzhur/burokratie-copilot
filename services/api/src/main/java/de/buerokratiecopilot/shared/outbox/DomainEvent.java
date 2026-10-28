package de.buerokratiecopilot.shared.outbox;

import java.util.Map;
import java.util.UUID;

/**
 * A domain event that describes something which happened in the system.
 *
 * <p>Domain events are the only way modules communicate asynchronously. They are written to the
 * transactional outbox inside the same transaction as the business change, so an event can never
 * be lost by a rolled-back action and can never announce something that did not happen
 * (spec §2.1, §16.3).</p>
 *
 * @param eventType     stable name of the event, for example {@code document.classified}
 * @param aggregateType kind of the aggregate the event belongs to, for example {@code documents}
 * @param aggregateId   identifier of that aggregate
 * @param tenantId      owning tenant; background work always runs inside this tenant context
 * @param payload       small, self-contained data needed by the consumer — never a whole entity
 */
public record DomainEvent(
        String eventType,
        String aggregateType,
        UUID aggregateId,
        UUID tenantId,
        Map<String, Object> payload) {

    public DomainEvent {
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("A domain event requires an event type");
        }
        if (aggregateType == null || aggregateType.isBlank()) {
            throw new IllegalArgumentException("A domain event requires an aggregate type");
        }
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    public static DomainEvent of(String eventType, String aggregateType, UUID aggregateId, UUID tenantId) {
        return new DomainEvent(eventType, aggregateType, aggregateId, tenantId, Map.of());
    }
}
