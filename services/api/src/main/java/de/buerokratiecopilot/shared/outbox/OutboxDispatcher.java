package de.buerokratiecopilot.shared.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import de.buerokratiecopilot.shared.config.BurokratieProperties;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * Delivers domain events from the outbox to their handlers.
 *
 * <p>Reliability properties (spec §16.3, §27):</p>
 * <ul>
 *   <li><strong>At-least-once:</strong> an event is only marked published after its handler
 *       returned successfully.</li>
 *   <li><strong>Exactly-once effect:</strong> every consumption is recorded in
 *       {@code outbox_consumptions}, so a redelivery after a crash is skipped instead of repeating
 *       the side effect.</li>
 *   <li><strong>Retry with backoff:</strong> a failing handler is retried with an exponential delay
 *       and ends up as a dead letter after the configured attempt budget, where an operator can see
 *       it instead of it disappearing.</li>
 *   <li><strong>Crash recovery:</strong> locks left behind by a worker that died are released, so a
 *       restart continues where the previous run stopped and a redeployment does not lose work.</li>
 *   <li><strong>Tenant context:</strong> every batch runs inside the tenant of its events, so
 *       background work is subject to the same Row Level Security as a user request.</li>
 *   <li><strong>Parallel workers:</strong> batches are claimed with {@code FOR UPDATE SKIP LOCKED},
 *       so several worker instances can run at the same time.</li>
 * </ul>
 */
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private static final Duration STALE_LOCK_AFTER = Duration.ofMinutes(10);
    private static final Duration BASE_BACKOFF = Duration.ofSeconds(10);
    private static final Duration MAX_BACKOFF = Duration.ofHours(1);

    private final OutboxEventRepository repository;
    private final OutboxStore store;
    private final TransactionTemplate transactionTemplate;
    private final Map<String, OutboxEventHandler> handlers = new HashMap<>();
    private final BurokratieProperties properties;
    private final String workerId;

    public OutboxDispatcher(OutboxEventRepository repository, OutboxStore store,
                            TransactionTemplate transactionTemplate,
                            List<OutboxEventHandler> eventHandlers, BurokratieProperties properties,
                            @Value("${burokratie.instance-id:}") String configuredInstanceId) {
        this.repository = repository;
        this.store = store;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
        for (OutboxEventHandler handler : eventHandlers) {
            OutboxEventHandler previous = handlers.put(handler.eventType(), handler);
            if (previous != null) {
                throw new IllegalStateException("Two handlers are registered for the event type "
                        + handler.eventType() + ": " + previous.getClass() + " and " + handler.getClass());
            }
        }
        this.workerId = configuredInstanceId == null || configuredInstanceId.isBlank()
                ? "worker-" + UUID.randomUUID().toString().substring(0, 8)
                : configuredInstanceId;
    }

    /** Scheduled entry point; also called directly by tests and maintenance tooling. */
    @Scheduled(fixedDelayString = "${burokratie.outbox.poll-interval-ms:5000}",
            initialDelayString = "${burokratie.outbox.initial-delay-ms:5000}")
    public void dispatch() {
        int delivered = 0;
        try {
            releaseStaleLocks();
            for (UUID tenantId : tenantsWithWork()) {
                delivered += RequestContext.runAs(tenantId, () -> dispatchTenant(tenantId));
            }
        }
        catch (RuntimeException ex) {
            // A failure here must never kill the scheduler thread: the next poll simply tries again.
            log.error("Outbox dispatch cycle failed", ex);
        }
        if (delivered > 0) {
            log.debug("Outbox delivered {} event(s)", delivered);
        }
    }

    /** Number of event types this instance knows how to handle. */
    /** The event types this instance has a handler for. */
    public java.util.Set<String> handledTypes() {
        return java.util.Set.copyOf(handlers.keySet());
    }

    public int handlerCount() {
        return handlers.size();
    }

    /** Handles a single event; exposed so a specific event can be replayed by an operator. */
    public boolean dispatch(OutboxEvent event) {
        OutboxEventHandler handler = handlers.get(event.getEventType());
        if (handler == null) {
            // An unknown event type is not retried forever: it is parked as a dead letter so the
            // condition is visible instead of silently filling the queue.
            log.warn("No handler registered for event type {} (event {})", event.getEventType(),
                    event.getEventId());
            return false;
        }
        handler.handle(event);
        return true;
    }

    private List<UUID> tenantsWithWork() {
        return transactionTemplate.execute(status -> store.findTenantsWithPendingEvents());
    }

    private int dispatchTenant(UUID tenantId) {
        List<OutboxEvent> batch = transactionTemplate.execute(status -> {
            List<UUID> ids = store.claimBatch(workerId, tenantId, properties.getOutbox().getBatchSize());
            if (ids.isEmpty()) {
                return List.<OutboxEvent>of();
            }
            return repository.findAllById(ids);
        });
        if (batch == null || batch.isEmpty()) {
            return 0;
        }

        int delivered = 0;
        for (OutboxEvent event : batch) {
            if (processOne(event)) {
                delivered++;
            }
        }
        return delivered;
    }

    private boolean processOne(OutboxEvent event) {
        String consumer = handlers.containsKey(event.getEventType())
                ? handlers.get(event.getEventType()).getClass().getName()
                : "unregistered:" + event.getEventType();

        try {
            return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
                OutboxEventHandler handler = handlers.get(event.getEventType());
                if (handler == null) {
                    event.markFailed("No handler registered for " + event.getEventType(), Instant.now(), true);
                    repository.save(event);
                    return false;
                }
                // Deduplication is part of the same transaction as the handler work: either the
                // side effect and its record both commit, or neither does.
                int inserted = store.recordConsumption(consumer, event.getEventId(), event.getTenantId());
                if (inserted == 0) {
                    event.markPublished(Instant.now());
                    repository.save(event);
                    return false;
                }
                handler.handle(event);
                event.markPublished(Instant.now());
                repository.save(event);
                return true;
            }));
        }
        catch (RuntimeException ex) {
            recordFailure(event, ex);
            return false;
        }
    }

    private void recordFailure(OutboxEvent event, RuntimeException ex) {
        log.warn("Outbox event {} ({}) failed on attempt {}", event.getEventId(), event.getEventType(),
                event.getAttempts() + 1, ex);
        transactionTemplate.executeWithoutResult(status -> {
            OutboxEvent managed = repository.findById(event.getId()).orElse(event);
            boolean deadLetter = managed.getAttempts() + 1 >= properties.getOutbox().getMaxAttempts();
            managed.markFailed(rootMessage(ex), Instant.now().plus(backoff(managed.getAttempts() + 1)), deadLetter);
            if (deadLetter) {
                log.error("Outbox event {} moved to dead letter after {} attempts: {}",
                        managed.getEventId(), managed.getAttempts(), rootMessage(ex));
            }
            repository.save(managed);
        });
    }

    private void releaseStaleLocks() {
        transactionTemplate.executeWithoutResult(status -> {
            int released = store.releaseStaleLocks(Instant.now().minus(STALE_LOCK_AFTER));
            if (released > 0) {
                log.warn("Released {} stale outbox lock(s) left behind by an interrupted worker", released);
            }
        });
    }

    private static Duration backoff(int attempt) {
        long seconds = BASE_BACKOFF.toSeconds() * (1L << Math.min(attempt, 10));
        return Duration.ofSeconds(Math.min(seconds, MAX_BACKOFF.toSeconds()));
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
