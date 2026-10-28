package de.buerokratiecopilot.documents.domain;

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
 * How a document entered the platform (spec §7).
 *
 * <p>{@code idempotencyKey} makes external ingest safe to retry: the unique index on
 * {@code (tenant_id, source_type, idempotency_key)} guarantees that redelivering the same external
 * object does not create a second document (spec §7.4).</p>
 */
@Entity
@Table(name = "document_sources")
public class DocumentSource {

    @Id
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 30)
    private Document.Source sourceType;

    @Column(name = "external_id", length = 300)
    private String externalId;

    @Column(name = "external_system", length = 80)
    private String externalSystem;

    @Column(name = "mail_message_id")
    private UUID mailMessageId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_metadata", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> rawMetadata = Map.of();

    @Column(name = "idempotency_key", length = 200)
    private String idempotencyKey;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt = Instant.now();

    protected DocumentSource() {
        // for JPA
    }

    public DocumentSource(UUID id, UUID documentId, UUID tenantId, Document.Source sourceType) {
        this.id = id;
        this.documentId = documentId;
        this.tenantId = tenantId;
        this.sourceType = sourceType;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public Document.Source getSourceType() {
        return sourceType;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public String getExternalSystem() {
        return externalSystem;
    }

    public void setExternalSystem(String externalSystem) {
        this.externalSystem = externalSystem;
    }

    public Map<String, Object> getRawMetadata() {
        return rawMetadata;
    }

    public void setRawMetadata(Map<String, Object> rawMetadata) {
        this.rawMetadata = rawMetadata == null ? Map.of() : rawMetadata;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
