package de.buerokratiecopilot.identity.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A server-side session.
 *
 * <p>The browser only ever holds an opaque random token; the database stores its SHA-256 hash,
 * so a leaked database row cannot be replayed as a valid session (spec §21.3). Sessions have
 * both an idle timeout and an absolute lifetime.</p>
 */
@Entity
@Table(name = "sessions")
public class UserSession {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "token_hash", nullable = false, length = 128)
    private String tokenHash;

    @Column(name = "mfa_verified", nullable = false)
    private boolean mfaVerified;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.INET)
    @Column(name = "ip_address")
    private java.net.InetAddress ipAddress;

    @Column(name = "user_agent", length = 400)
    private String userAgent;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "absolute_expires_at", nullable = false)
    private Instant absoluteExpiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_reason", length = 100)
    private String revokedReason;

    protected UserSession() {
        // for JPA
    }

    public UserSession(UUID id, UUID userId, String tokenHash, Instant expiresAt, Instant absoluteExpiresAt) {
        this.id = id;
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.absoluteExpiresAt = absoluteExpiresAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public boolean isMfaVerified() {
        return mfaVerified;
    }

    public void setMfaVerified(boolean mfaVerified) {
        this.mfaVerified = mfaVerified;
    }

    public java.net.InetAddress getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(java.net.InetAddress ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getAbsoluteExpiresAt() {
        return absoluteExpiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public String getRevokedReason() {
        return revokedReason;
    }

    /** Slides the idle timeout forward, but never past the absolute lifetime. */
    public void touch(Instant now, java.time.Duration idleTimeout) {
        this.lastSeenAt = now;
        Instant candidate = now.plus(idleTimeout);
        this.expiresAt = candidate.isAfter(absoluteExpiresAt) ? absoluteExpiresAt : candidate;
    }

    public void revoke(String reason, Instant when) {
        this.revokedAt = when;
        this.revokedReason = reason;
    }

    public boolean isValid(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt) && now.isBefore(absoluteExpiresAt);
    }
}
