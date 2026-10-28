package de.buerokratiecopilot.documents.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Extracted text of one page (spec §9.2).
 *
 * <p>The engine, its language and its confidence are stored next to the text: a value taken from a
 * page that came out of an OCR engine with mediocre confidence must be distinguishable from one read
 * from a digital text layer, both for the reviewer and for the threshold that decides whether a
 * human has to look at it.</p>
 */
@Entity
@Table(name = "document_pages")
public class DocumentPage {

    @Id
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "version_id", nullable = false)
    private UUID versionId;

    @Column(name = "page_number", nullable = false)
    private int pageNumber;

    @Column(name = "text_content")
    private String textContent;

    @Column(name = "ocr_used", nullable = false)
    private boolean ocrUsed;

    @Column(name = "ocr_engine", length = 60)
    private String ocrEngine;

    @Column(name = "ocr_language", length = 40)
    private String ocrLanguage;

    @Column(name = "ocr_confidence", precision = 5, scale = 4)
    private java.math.BigDecimal ocrConfidence;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected DocumentPage() {
        // for JPA
    }

    public DocumentPage(UUID id, UUID documentId, UUID versionId, int pageNumber) {
        this.id = id;
        this.documentId = documentId;
        this.versionId = versionId;
        this.pageNumber = pageNumber;
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

    public int getPageNumber() {
        return pageNumber;
    }

    public String getTextContent() {
        return textContent;
    }

    public void setTextContent(String textContent) {
        this.textContent = textContent;
    }

    public boolean isOcrUsed() {
        return ocrUsed;
    }

    public void setOcrUsed(boolean ocrUsed) {
        this.ocrUsed = ocrUsed;
    }

    public String getOcrEngine() {
        return ocrEngine;
    }

    public void setOcrEngine(String ocrEngine) {
        this.ocrEngine = ocrEngine;
    }

    public String getOcrLanguage() {
        return ocrLanguage;
    }

    public void setOcrLanguage(String ocrLanguage) {
        this.ocrLanguage = ocrLanguage;
    }

    public java.math.BigDecimal getOcrConfidence() {
        return ocrConfidence;
    }

    public void setOcrConfidence(java.math.BigDecimal ocrConfidence) {
        this.ocrConfidence = ocrConfidence;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isEmpty() {
        return textContent == null || textContent.isBlank();
    }
}
