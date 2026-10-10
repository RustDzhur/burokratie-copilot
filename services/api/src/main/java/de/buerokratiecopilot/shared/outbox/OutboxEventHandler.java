package de.buerokratiecopilot.shared.outbox;

/**
 * Consumer of one or more domain events.
 *
 * <p>Handlers must be idempotent in effect: the outbox guarantees at-least-once delivery, and the
 * dispatcher additionally records every consumption, but a handler that sends an e-mail or creates a
 * payment still has to tolerate being called again after a crash (spec §16.3).</p>
 */
public interface OutboxEventHandler {

    /** Event type this handler subscribes to, for example {@code document.classified}. */
    String eventType();

    /** Handles one event. Throwing marks the delivery as failed and schedules a retry. */
    void handle(OutboxEvent event);
}
