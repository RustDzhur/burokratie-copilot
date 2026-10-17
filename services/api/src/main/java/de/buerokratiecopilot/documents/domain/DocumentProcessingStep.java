package de.buerokratiecopilot.documents.domain;

import java.time.Duration;
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
 * One step of the document processing pipeline (spec §9.1).
 *
 * <p>Every step carries its own status, attempt counter and diagnostics, which is what makes
 * "which stage is this document in, and why" answerable — and what allows a single failed step to be
 * retried without reprocessing the whole document.</p>
 */
@Entity
@Table(name = "document_processing_steps")
public class DocumentProcessingStep {

    /**
     * The pipeline of spec §9.1, in execution order.
     *
     * <p><strong>Not every value here runs.</strong> Five of them — {@link #NOT_IMPLEMENTED} — are declared
     * because the specification names them, and the enum has to be able to store them if they ever arrive,
     * but nothing executes them. The list used to be invisible: the enum said fifteen stages, the pipeline ran
     * ten, and no test compared the two.</p>
     *
     * <p>Keeping them explicit is the honest option: deleting them would quietly change the domain to match
     * the code, and the specification would still ask for them. {@code DocumentProcessingStepCoverageTest}
     * fails if an enum value is neither executed nor listed below, so the next person cannot add a stage and
     * forget to wire it.</p>
     */
    public enum Step {
        SECURITY_SCAN,
        HASH,
        DUPLICATE_CHECK,
        FORMAT_DETECT,
        TEXT_EXTRACT,
        OCR,
        LANGUAGE_DETECT,
        CLASSIFY,
        FIELD_EXTRACT,
        MATCHING,
        CONTRADICTION_CHECK,
        ACTION_DERIVATION,
        RISK_ASSESSMENT,
        PERSIST,
        INDEX
    }

    /**
     * The stages the specification names and the product does not run.
     *
     * <p>They are not silently missing: this list is checked against the enum by a test, so the gap is a
     * recorded decision rather than an oversight.</p>
     */
    public static final java.util.Set<Step> NOT_IMPLEMENTED = java.util.Set.of(
            Step.CONTRADICTION_CHECK,
            Step.ACTION_DERIVATION,
            Step.RISK_ASSESSMENT,
            Step.PERSIST,
            Step.INDEX);

    public enum Status {
        PENDING,
        RUNNING,
        SUCCEEDED,
        FAILED,
        SKIPPED
    }

    @Id
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "version_id")
    private UUID versionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private Step step;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(nullable = false)
    private int attempt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> diagnostics = Map.of();

    @Column(name = "error_code", length = 80)
    private String errorCode;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected DocumentProcessingStep() {
        // for JPA
    }

    public DocumentProcessingStep(UUID id, UUID documentId, UUID versionId, Step step) {
        this.id = id;
        this.documentId = documentId;
        this.versionId = versionId;
        this.step = step;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public UUID getVersionId() {
        return versionId;
    }

    public Step getStep() {
        return step;
    }

    public Status getStatus() {
        return status;
    }

    public int getAttempt() {
        return attempt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public Map<String, Object> getDiagnostics() {
        return diagnostics;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void start() {
        this.status = Status.RUNNING;
        this.startedAt = Instant.now();
        this.attempt++;
        this.updatedAt = Instant.now();
    }

    public void succeed(Map<String, Object> diagnostics) {
        this.status = Status.SUCCEEDED;
        this.diagnostics = diagnostics == null ? Map.of() : diagnostics;
        this.errorCode = null;
        this.errorMessage = null;
        finish();
    }

    /**
     * Records that the step was deliberately not performed.
     *
     * <p>Distinct from a failure and from a success: a skipped step is visible as such, so nobody can
     * mistake a document that was not processed for one that was.</p>
     */
    public void skip(String reason) {
        this.status = Status.SKIPPED;
        this.errorMessage = reason;
        finish();
    }

    public void fail(String errorCode, String message) {
        this.status = Status.FAILED;
        this.errorCode = errorCode;
        this.errorMessage = message;
        finish();
    }

    public void resetForRetry() {
        this.status = Status.PENDING;
        this.errorCode = null;
        this.errorMessage = null;
        this.updatedAt = Instant.now();
    }

    private void finish() {
        this.finishedAt = Instant.now();
        this.durationMs = startedAt == null
                ? null
                : Duration.between(startedAt, finishedAt).toMillis();
        this.updatedAt = Instant.now();
    }

    public boolean isFinished() {
        return status == Status.SUCCEEDED || status == Status.SKIPPED;
    }
}
