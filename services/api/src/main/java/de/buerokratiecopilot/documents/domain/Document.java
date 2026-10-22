package de.buerokratiecopilot.documents.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * The central entity of the platform (spec §6.2).
 *
 * <p>Only metadata, links and extracted text live in PostgreSQL; the bytes live in the object store
 * and are referenced through {@link DocumentVersion} and {@link DocumentBlob}. The entity maps the
 * columns the domain logic needs and deliberately leaves presentation-only columns (search vector,
 * confidence snapshots, …) to the database.</p>
 */
@Entity
@Table(name = "documents")
public class Document {

    /**
     * Processing pipeline states (spec §6.3).
     *
     * <p>Transitions are validated by {@link DocumentStatusMachine} in the service layer; the
     * database only guarantees that no unknown value can be stored.</p>
     */
    public enum ProcessingStatus {
        RECEIVED, QUARANTINED, OCR_PENDING, EXTRACTING, CLASSIFYING, MATCHING,
        REVIEW_REQUIRED, READY, ACTION_PENDING, COMPLETED,
        FAILED, DUPLICATE, UNSUPPORTED, CANCELLED, ARCHIVED, LEGAL_HOLD
    }

    /** How a document entered the system (spec §7). */
    public enum Source {
        UPLOAD, EMAIL, SCANNER, API, FOLDER_IMPORT, MOBILE, GENERATED, MIGRATION
    }

    public enum ReviewStatus {
        NOT_REQUIRED, PENDING, IN_REVIEW, APPROVED, REJECTED
    }

    public enum Confidentiality {
        PUBLIC, INTERNAL, CONFIDENTIAL, STRICT
    }

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "workspace_id")
    private UUID workspaceId;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "original_filename", nullable = false, length = 400)
    private String originalFilename;

    /** Server-generated name; the client-supplied name is never used for storage addressing. */
    @Column(name = "internal_name", nullable = false, length = 400)
    private String internalName;

    @Column(name = "mime_type", nullable = false, length = 150)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "current_version", nullable = false)
    private int currentVersion = 1;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Source source = Source.UPLOAD;

    @Column(name = "sender", length = 400)
    private String sender;

    /** Address, telephone, e-mail, website, VAT id and IBAN of the sender as read from the letter. */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "sender_details", columnDefinition = "jsonb", nullable = false)
    private java.util.Map<String, Object> senderDetails = java.util.Map.of();

    /** The contact this sender is, found or created by the platform. */
    @Column(name = "sender_contact_id")
    private UUID senderContactId;

    /** LETTER read by the platform, MAIL taken from the mail, PERSON typed or confirmed by a person. */
    @Column(name = "sender_source", length = 10)
    private String senderSource;

    @Column(name = "recipient", length = 400)
    private String recipient;

    @Column(name = "document_date")
    private java.time.LocalDate documentDate;

    @Column(name = "document_number", length = 120)
    private String documentNumber;

    @Column(name = "document_type_id")
    private UUID documentTypeId;

    @Column(name = "language", length = 10)
    private String language;

    @Column(name = "summary")
    private String summary;

    @Column(name = "extracted_text")
    private String extractedText;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, length = 30)
    private ProcessingStatus processingStatus = ProcessingStatus.RECEIVED;

    /**
     * Where this voucher stands in the office's work (§8.1), which is not the same question as
     * {@link #processingStatus}: a file can be fully extracted and still untouched in the queue.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "beleg_status", nullable = false, length = 20)
    private BelegStatus belegStatus = BelegStatus.NEU;

    /** Null means "Ohne Mandant": shown in the queue, refused by the export, never guessed (§5.2). */
    @Column(name = "mandant_id")
    private UUID mandantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 30)
    private ReviewStatus reviewStatus = ReviewStatus.NOT_REQUIRED;

    @Enumerated(EnumType.STRING)
    @Column(name = "confidentiality", nullable = false, length = 30)
    private Confidentiality confidentiality = Confidentiality.INTERNAL;

    @Column(name = "legal_hold", nullable = false)
    private boolean legalHold;

    @Column(name = "retention_policy_id")
    private UUID retentionPolicyId;

    /** Set when this document is recognised as a duplicate of an existing one (spec §9.1). */
    @Column(name = "duplicate_of_document_id")
    private UUID duplicateOfDocumentId;

    /** Whom the document is addressed to (a member of the household, the person, the company); null when unknown. */
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt = Instant.now();

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "created_by")
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Document() {
        // for JPA
    }

    public Document(UUID id, UUID tenantId, String originalFilename, String internalName,
                    String mimeType, long sizeBytes, String contentHash, Source source) {
        this.id = id;
        this.tenantId = tenantId;
        this.originalFilename = originalFilename;
        this.internalName = internalName;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.contentHash = contentHash;
        this.source = source;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(UUID workspaceId) {
        this.workspaceId = workspaceId;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(UUID organizationId) {
        this.organizationId = organizationId;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getInternalName() {
        return internalName;
    }

    public String getMimeType() {
        return mimeType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getContentHash() {
        return contentHash;
    }

    public int getCurrentVersion() {
        return currentVersion;
    }

    public void setCurrentVersion(int currentVersion) {
        this.currentVersion = currentVersion;
    }

    public Source getSource() {
        return source;
    }

    public String getSender() {
        return sender;
    }

    public java.util.Map<String, Object> getSenderDetails() {
        return senderDetails;
    }

    public void setSenderDetails(java.util.Map<String, Object> senderDetails) {
        this.senderDetails = senderDetails == null ? java.util.Map.of() : senderDetails;
    }

    public UUID getSenderContactId() {
        return senderContactId;
    }

    public void setSenderContactId(UUID senderContactId) {
        this.senderContactId = senderContactId;
    }

    public String getSenderSource() {
        return senderSource;
    }

    public void setSenderSource(String senderSource) {
        this.senderSource = senderSource;
    }

    public void setSender(String sender) {
        this.sender = sender;
    }

    public String getRecipient() {
        return recipient;
    }

    public void setRecipient(String recipient) {
        this.recipient = recipient;
    }

    public java.time.LocalDate getDocumentDate() {
        return documentDate;
    }

    public void setDocumentDate(java.time.LocalDate documentDate) {
        this.documentDate = documentDate;
    }

    public String getDocumentNumber() {
        return documentNumber;
    }

    public void setDocumentNumber(String documentNumber) {
        this.documentNumber = documentNumber;
    }

    public UUID getDocumentTypeId() {
        return documentTypeId;
    }

    public void setDocumentTypeId(UUID documentTypeId) {
        this.documentTypeId = documentTypeId;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getExtractedText() {
        return extractedText;
    }

    public void setExtractedText(String extractedText) {
        this.extractedText = extractedText;
    }

    public BelegStatus getBelegStatus() {
        return belegStatus;
    }

    public void setBelegStatus(BelegStatus belegStatus) {
        this.belegStatus = belegStatus;
    }

    public UUID getMandantId() {
        return mandantId;
    }

    public void setMandantId(UUID mandantId) {
        this.mandantId = mandantId;
    }

    public ProcessingStatus getProcessingStatus() {
        return processingStatus;
    }

    public void setProcessingStatus(ProcessingStatus processingStatus) {
        this.processingStatus = processingStatus;
    }

    public ReviewStatus getReviewStatus() {
        return reviewStatus;
    }

    public void setReviewStatus(ReviewStatus reviewStatus) {
        this.reviewStatus = reviewStatus;
    }

    public Confidentiality getConfidentiality() {
        return confidentiality;
    }

    public void setConfidentiality(Confidentiality confidentiality) {
        this.confidentiality = confidentiality;
    }

    public boolean isLegalHold() {
        return legalHold;
    }

    public void setLegalHold(boolean legalHold) {
        this.legalHold = legalHold;
    }

    public UUID getRetentionPolicyId() {
        return retentionPolicyId;
    }

    public void setRetentionPolicyId(UUID retentionPolicyId) {
        this.retentionPolicyId = retentionPolicyId;
    }

    public UUID getDuplicateOfDocumentId() {
        return duplicateOfDocumentId;
    }

    public void setDuplicateOfDocumentId(UUID duplicateOfDocumentId) {
        this.duplicateOfDocumentId = duplicateOfDocumentId;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public void setArchivedAt(Instant archivedAt) {
        this.archivedAt = archivedAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(UUID createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    /** A document on legal hold must not be deleted, whatever the retention policy says (§22). */
    public boolean isDeletable() {
        return !legalHold && deletedAt == null;
    }
}
