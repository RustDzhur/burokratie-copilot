package de.buerokratiecopilot.shared.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Component;

/**
 * The statements that must be expressed in native SQL: batch claiming, consumption recording and
 * stale lock recovery.
 *
 * <p>They live here instead of on the Spring Data interface because they are data modifying
 * statements whose result matters (a claim returns the rows it took), which is not what
 * {@code @Modifying} is meant for. Keeping them in one place also makes the concurrency behaviour
 * easy to review.</p>
 */
@Component
public class OutboxStore {

    @PersistenceContext
    private EntityManager entityManager;

    /** Tenants with work waiting, so every batch can run inside a tenant context. */
    @SuppressWarnings("unchecked")
    public List<UUID> findTenantsWithPendingEvents() {
        return entityManager.createNativeQuery("""
                SELECT DISTINCT tenant_id FROM outbox_events
                WHERE status IN ('PENDING', 'FAILED') AND available_at <= now() AND tenant_id IS NOT NULL
                """).getResultList();
    }

    /**
     * Atomically claims a batch for this worker.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} lets several worker instances run in parallel without
     * fighting over the same rows; {@code RETURNING id} makes claim and read one step.</p>
     */
    @SuppressWarnings("unchecked")
    public List<UUID> claimBatch(String worker, UUID tenantId, int batchSize) {
        return entityManager.createNativeQuery("""
                UPDATE outbox_events SET status = 'PROCESSING', locked_by = :worker, locked_at = now()
                WHERE id IN (
                    SELECT id FROM outbox_events
                    WHERE status IN ('PENDING', 'FAILED')
                      AND available_at <= now()
                      AND (CAST(:tenantId AS uuid) IS NULL OR tenant_id = CAST(:tenantId AS uuid))
                    ORDER BY available_at ASC
                    FOR UPDATE SKIP LOCKED
                    LIMIT :batchSize
                )
                RETURNING id
                """)
                .setParameter("worker", worker)
                .setParameter("tenantId", tenantId)
                .setParameter("batchSize", batchSize)
                .getResultList();
    }

    /**
     * Records that a consumer handled an event.
     *
     * @return 1 when the pair is new, 0 when this consumer already handled the event
     */
    public int recordConsumption(String consumer, UUID eventId, UUID tenantId) {
        return entityManager.createNativeQuery("""
                INSERT INTO outbox_consumptions (consumer, event_id, tenant_id)
                VALUES (:consumer, :eventId, :tenantId)
                ON CONFLICT (consumer, event_id) DO NOTHING
                """)
                .setParameter("consumer", consumer)
                .setParameter("eventId", eventId)
                .setParameter("tenantId", tenantId)
                .executeUpdate();
    }

    /** Recovers events whose worker died mid processing (spec §27: recovery after a restart). */
    public int releaseStaleLocks(Instant staleBefore) {
        return entityManager.createNativeQuery("""
                UPDATE outbox_events
                SET status = 'PENDING', locked_by = NULL, locked_at = NULL, available_at = now()
                WHERE status = 'PROCESSING' AND locked_at < :staleBefore
                """)
                .setParameter("staleBefore", staleBefore)
                .executeUpdate();
    }
}
