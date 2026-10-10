package de.buerokratiecopilot.shared.idempotency;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> {

    Optional<IdempotencyRecord> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);

    /**
     * Claims a key.
     *
     * <p>{@code ON CONFLICT DO NOTHING} plus the unique index is what makes the claim atomic: exactly
     * one concurrent request can win, and the losers get 0 rows back and then inspect the winner's
     * record. Doing this in the database (instead of a read-then-write) is why a double click or a
     * retried request cannot create two invoices.</p>
     *
     * @return 1 when this caller owns the key, 0 when somebody else already did
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO idempotency_records
                (id, tenant_id, idempotency_key, request_method, request_path, request_hash,
                 user_id, status, created_at, expires_at)
            VALUES
                (:id, :tenantId, :key, :method, :path, :hash, :userId, 'IN_PROGRESS', now(), :expiresAt)
            ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int claim(@Param("id") UUID id,
              @Param("tenantId") UUID tenantId,
              @Param("key") String key,
              @Param("method") String method,
              @Param("path") String path,
              @Param("hash") String hash,
              @Param("userId") UUID userId,
              @Param("expiresAt") Instant expiresAt);

    /**
     * Takes over a claim whose original request died before finishing, so a client that gives up and
     * retries is not blocked forever by a stale in-progress marker.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update IdempotencyRecord r
               set r.status = de.buerokratiecopilot.shared.idempotency.IdempotencyRecord$Status.IN_PROGRESS,
                   r.createdAt = :now, r.completedAt = null, r.responseStatus = null, r.responseBody = null
             where r.id = :id
            """)
    int takeOver(@Param("id") UUID id, @Param("now") Instant now);
}
