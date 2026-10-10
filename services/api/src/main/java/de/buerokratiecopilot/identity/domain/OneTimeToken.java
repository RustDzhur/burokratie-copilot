package de.buerokratiecopilot.identity.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Single-use token for e-mail verification, password reset or the initial administrator
 * bootstrap.
 *
 * <p>Only the hash is persisted, tokens expire, and consuming one is a one-way transition —
 * a reset link cannot be replayed (spec §21.3).</p>
 */
@Entity
@Table(name = "one_time_tokens")
public class OneTimeToken {

    public enum Purpose {
        EMAIL_VERIFICATION,
        PASSWORD_RESET,
        MFA_ENROLLMENT,
        ADMIN_BOOTSTRAP
    }

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private Purpose purpose;

    @Column(name = "token_hash", nullable = false, length = 128)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected OneTimeToken() {
        // for JPA
    }

    public OneTimeToken(UUID id, UUID userId, Purpose purpose, String tokenHash, Instant expiresAt) {
        this.id = id;
        this.userId = userId;
        this.purpose = purpose;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public Purpose getPurpose() {
        return purpose;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isUsable(Instant now, Purpose expectedPurpose) {
        return purpose == expectedPurpose && consumedAt == null && now.isBefore(expiresAt);
    }

    public void consume(Instant when) {
        this.consumedAt = when;
    }
}
