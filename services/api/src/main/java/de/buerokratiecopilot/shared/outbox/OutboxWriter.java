package de.buerokratiecopilot.shared.outbox;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes domain events into the transactional outbox.
 *
 * <p>{@link #publish} requires an active transaction on purpose: it is the mechanism that makes
 * "the business change and the announcement of it" a single atomic unit. Calling it outside a
 * transaction is a programming error and fails immediately instead of producing a system that
 * announces changes that were rolled back (spec §16.3).</p>
 */
@Service
public class OutboxWriter {

    private final OutboxEventRepository repository;

    public OutboxWriter(OutboxEventRepository repository) {
        this.repository = repository;
    }

    /**
     * Stores the event inside the caller's transaction.
     *
     * @throws org.springframework.transaction.IllegalTransactionStateException when no transaction
     *         is active
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent publish(DomainEvent event) {
        return repository.save(OutboxEvent.from(event));
    }

    /**
     * Stores an event in its own transaction.
     *
     * <p>Only for events that are not tied to another change (for example an operational signal).
     * Business events must go through {@link #publish}.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OutboxEvent publishStandalone(DomainEvent event) {
        return repository.save(OutboxEvent.from(event));
    }
}
