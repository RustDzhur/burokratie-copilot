package de.buerokratiecopilot.shared.idempotency;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A recorded idempotent request (spec §7.4, §16.3).
 *
 * <p>The unique key is {@code (tenant_id, idempotency_key)}, so a client can only ever collide with
 * its own tenant — one customer can never observe or replay the request of another.</p>
 */
@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord {

    public enum Status {
        IN_PROGRESS,
        COMPLETED,
        FAILED
    }

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "idempotency_key", nullable = false, length = 200)
    private String idempotencyKey;

    @Column(name = "request_method", nullable = false, length = 10)
    private String requestMethod;

    @Column(name = "request_path", nullable = false, length = 400)
    private String requestPath;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "user_id")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.IN_PROGRESS;

    @Column(name = "response_status")
    private Integer responseStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body", columnDefinition = "jsonb")
    private Map<String, Object> responseBody;

    @Column(name = "resource_type", length = 60)
    private String resourceType;

    @Column(name = "resource_id")
    private UUID resourceId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected IdempotencyRecord() {
        // for JPA
    }

    public static IdempotencyRecord inProgress(UUID tenantId, String key, String method, String path,
                                               String requestHash, UUID userId, Instant expiresAt) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.id = UUID.randomUUID();
        record.tenantId = tenantId;
        record.idempotencyKey = key;
        record.requestMethod = method;
        record.requestPath = path;
        record.requestHash = requestHash;
        record.userId = userId;
        record.status = Status.IN_PROGRESS;
        record.expiresAt = expiresAt;
        return record;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestMethod() {
        return requestMethod;
    }

    public String getRequestPath() {
        return requestPath;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public UUID getUserId() {
        return userId;
    }

    public Status getStatus() {
        return status;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public Map<String, Object> getResponseBody() {
        return responseBody;
    }

    public String getResourceType() {
        return resourceType;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    /** The same key with a different payload is a client bug and must not be replayed. */
    public boolean matches(String method, String path, String hash) {
        return requestMethod.equalsIgnoreCase(method) && requestPath.equals(path) && requestHash.equals(hash);
    }

    public void complete(int responseStatus, Map<String, Object> body, String resourceType, UUID resourceId) {
        this.status = Status.COMPLETED;
        this.responseStatus = responseStatus;
        this.responseBody = body;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.completedAt = Instant.now();
    }
}
