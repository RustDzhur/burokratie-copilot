package de.buerokratiecopilot.shared.context;

import java.util.Optional;
import java.util.UUID;

/**
 * Per-request context propagated on the executing thread.
 *
 * <p>It deliberately carries only identifiers, never entities: the tenant of a request is
 * derived from the authenticated session (spec §3.2) and is never read from a request body,
 * query parameter or header. Background jobs set the same context explicitly so that every
 * outbox consumer and worker task runs inside the correct tenant scope.</p>
 */
public final class RequestContext {

    /** Who caused an action — used for the audit trail (§21.4). */
    public enum ActorType {
        USER,
        SYSTEM,
        API_TOKEN,
        WORKFLOW,
        AI,
        DEVICE
    }

    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private RequestContext() {
    }

    private record State(UUID tenantId, UUID userId, String correlationId, ActorType actorType) {
    }

    public static void setTenantId(UUID tenantId) {
        State current = current();
        CURRENT.set(new State(tenantId, current.userId(), current.correlationId(), current.actorType()));
    }

    public static void setUserId(UUID userId) {
        State current = current();
        CURRENT.set(new State(current.tenantId(), userId, current.correlationId(), current.actorType()));
    }

    public static void setCorrelationId(String correlationId) {
        State current = current();
        CURRENT.set(new State(current.tenantId(), current.userId(), correlationId, current.actorType()));
    }

    public static void setActorType(ActorType actorType) {
        State current = current();
        CURRENT.set(new State(current.tenantId(), current.userId(), current.correlationId(), actorType));
    }

    /** Runs a block in an explicit tenant scope — used by workers and tests. */
    public static <T> T runAs(UUID tenantId, java.util.function.Supplier<T> action) {
        State previous = CURRENT.get();
        try {
            setTenantId(tenantId);
            return action.get();
        }
        finally {
            if (previous == null) {
                CURRENT.remove();
            }
            else {
                CURRENT.set(previous);
            }
        }
    }

    public static void runAs(UUID tenantId, Runnable action) {
        runAs(tenantId, () -> {
            action.run();
            return null;
        });
    }

    public static Optional<UUID> tenantId() {
        return Optional.ofNullable(CURRENT.get()).map(State::tenantId);
    }

    public static Optional<UUID> userId() {
        return Optional.ofNullable(CURRENT.get()).map(State::userId);
    }

    public static Optional<String> correlationId() {
        return Optional.ofNullable(CURRENT.get()).map(State::correlationId);
    }

    public static ActorType actorType() {
        State current = CURRENT.get();
        return current == null || current.actorType() == null ? ActorType.SYSTEM : current.actorType();
    }

    public static void clear() {
        CURRENT.remove();
    }

    private static State current() {
        State state = CURRENT.get();
        return state == null ? new State(null, null, null, ActorType.USER) : state;
    }
}
