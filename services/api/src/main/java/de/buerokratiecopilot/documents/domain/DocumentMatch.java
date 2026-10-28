package de.buerokratiecopilot.documents.domain;

import java.math.BigDecimal;
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
 * A proposed link between a document and another object (spec §9.5).
 *
 * <p>The score is stored together with the evidence that produced it, and the state separates a
 * proposal from a decision. That is what allows the platform to say "this looks like case 2026-114
 * because the reference appears on page 1" and to let a person disagree.</p>
 */
@Entity
@Table(name = "document_matches")
public class DocumentMatch {

    public enum MatchType {
        CASE, CONTACT, CONTRACT, INVOICE, OBLIGATION, TASK
    }

    public enum Status {
        SUGGESTED, CONFIRMED, REJECTED
    }

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "version_id")
    private UUID versionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_type", nullable = false, length = 20)
    private MatchType matchType;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal score;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> rationale = Map.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> evidence = Map.of();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.SUGGESTED;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected DocumentMatch() {
        // for JPA
    }

    public DocumentMatch(UUID id, UUID tenantId, UUID documentId, UUID versionId, MatchType matchType,
                         UUID targetId, BigDecimal score) {
        this.id = id;
        this.tenantId = tenantId;
        this.documentId = documentId;
        this.versionId = versionId;
        this.matchType = matchType;
        this.targetId = targetId;
        this.score = score;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public UUID getVersionId() {
        return versionId;
    }

    public MatchType getMatchType() {
        return matchType;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public BigDecimal getScore() {
        return score;
    }

    public void setScore(BigDecimal score) {
        this.score = score;
    }

    public Map<String, Object> getRationale() {
        return rationale;
    }

    public void setRationale(Map<String, Object> rationale) {
        this.rationale = rationale == null ? Map.of() : rationale;
    }

    public Map<String, Object> getEvidence() {
        return evidence;
    }

    public void setEvidence(Map<String, Object> evidence) {
        this.evidence = evidence == null ? Map.of() : evidence;
    }

    public Status getStatus() {
        return status;
    }

    public UUID getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    /** Records a person's decision. Confirming and rejecting are the only two outcomes. */
    public void decide(Status decision, UUID userId) {
        if (decision == Status.SUGGESTED) {
            throw new IllegalArgumentException("A decision cannot be 'suggested'");
        }
        this.status = decision;
        this.decidedBy = userId;
        this.decidedAt = Instant.now();
    }

    /** Applies a rule based confirmation above the automatic threshold. */
    public void confirmByRule(BigDecimal confidence, Map<String, Object> why) {
        this.status = Status.CONFIRMED;
        this.score = confidence;
        this.rationale = why;
        this.decidedAt = Instant.now();
    }

    /**
     * Puts a rejected pair in front of a person again because new data supports it much more strongly than what was
     * rejected. The person decides again; nothing is confirmed by this.
     */
    public void reopen(UUID newVersionId, BigDecimal newScore, Map<String, Object> why, Map<String, Object> newEvidence) {
        this.status = Status.SUGGESTED;
        this.versionId = newVersionId;
        this.score = newScore;
        this.rationale = why;
        this.evidence = newEvidence == null ? Map.of() : newEvidence;
        this.decidedBy = null;
        this.decidedAt = null;
    }

    public boolean isSuggested() {
        return status == Status.SUGGESTED;
    }
}
