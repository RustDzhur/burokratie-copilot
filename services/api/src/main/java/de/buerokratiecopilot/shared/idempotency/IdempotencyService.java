package de.buerokratiecopilot.shared.idempotency;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * Claiming, completing and releasing idempotent requests (spec §7.4, §16.3).
 *
 * <p>Every method runs in its own transaction on purpose: the claim must be visible to other
 * requests immediately, and it must survive a rollback of the business transaction. Otherwise a
 * failed attempt would either block the key forever or allow a duplicate on retry.</p>
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    /** How long a claimed key is remembered. Long enough for any realistic client retry. */
    private static final Duration RETENTION = Duration.ofHours(24);

    /** A claim older than this is considered abandoned (the request died mid-flight). */
    private static final Duration STALE_CLAIM_AFTER = Duration.ofMinutes(2);

    private final IdempotencyRecordRepository repository;

    public IdempotencyService(IdempotencyRecordRepository repository) {
        this.repository = repository;
    }

    /** Outcome of a claim attempt. */
    public enum Outcome {
        /** This caller owns the key and has to perform the operation. */
        PROCEED,
        /** The operation already ran; the stored response must be returned unchanged. */
        REPLAY,
        /** Another request with the same key is running right now. */
        IN_PROGRESS,
        /** The key was already used for a different request; replaying it would be wrong. */
        KEY_REUSED
    }

    public record Claim(Outcome outcome, IdempotencyRecord record) {
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Claim claim(UUID tenantId, String key, String method, String path, String requestHash) {
        UUID id = UUID.randomUUID();
        UUID userId = RequestContext.userId().orElse(null);
        int inserted = repository.claim(id, tenantId, key, method, path, requestHash, userId,
                Instant.now().plus(RETENTION));
        if (inserted == 1) {
            return new Claim(Outcome.PROCEED, repository.findById(id).orElseThrow());
        }

        IdempotencyRecord existing = repository.findByTenantIdAndIdempotencyKey(tenantId, key)
                .orElseThrow(() -> new IllegalStateException(
                        "Idempotency claim conflicted but no record exists — check the unique index"));

        if (!existing.matches(method, path, requestHash)) {
            return new Claim(Outcome.KEY_REUSED, existing);
        }
        if (existing.getStatus() == IdempotencyRecord.Status.COMPLETED) {
            return new Claim(Outcome.REPLAY, existing);
        }
        if (existing.getStatus() == IdempotencyRecord.Status.IN_PROGRESS
                && existing.getCreatedAt().isAfter(Instant.now().minus(STALE_CLAIM_AFTER))) {
            return new Claim(Outcome.IN_PROGRESS, existing);
        }

        // Stale claim: the previous attempt died without finishing, so this caller takes over.
        log.warn("Taking over a stale idempotency claim for key {}", key);
        repository.takeOver(existing.getId(), Instant.now());
        return new Claim(Outcome.PROCEED, repository.findById(existing.getId()).orElseThrow());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID recordId, int responseStatus, Map<String, Object> responseBody) {
        repository.findById(recordId).ifPresent(record ->
                repository.save(completeAndReturn(record, responseStatus, responseBody)));
    }

    private static IdempotencyRecord completeAndReturn(IdempotencyRecord record, int responseStatus,
                                                       Map<String, Object> responseBody) {
        record.complete(responseStatus, responseBody, null, null);
        return record;
    }

    /**
     * Releases a claim after a failed attempt.
     *
     * <p>A failure must not be replayed: the client has to be able to fix the input and try again with
     * the same key. Only successful responses are remembered.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(UUID recordId) {
        repository.findById(recordId).ifPresent(repository::delete);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int purgeExpired() {
        Instant now = Instant.now();
        int removed = 0;
        for (IdempotencyRecord record : repository.findAll()) {
            if (record.getExpiresAt().isBefore(now)) {
                repository.delete(record);
                removed++;
            }
        }
        return removed;
    }

    Optional<IdempotencyRecord> find(UUID tenantId, String key) {
        return repository.findByTenantIdAndIdempotencyKey(tenantId, key);
    }
}
