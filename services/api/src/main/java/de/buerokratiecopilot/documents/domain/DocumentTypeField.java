package de.buerokratiecopilot.documents.domain;

import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A field that can be extracted from a document type (spec §9.4, §10).
 *
 * <p>The definition is configuration: which fields a type has, which of them are mandatory, which are
 * critical for a later decision, and how to find them. {@code isCritical} is what the review centre
 * uses to decide which values a human must confirm before automation continues.</p>
 */
@Entity
@Table(name = "document_type_fields")
public class DocumentTypeField {

    @Id
    private UUID id;

    @Column(name = "document_type_id", nullable = false)
    private UUID documentTypeId;

    @Column(name = "field_key", nullable = false, length = 80)
    private String fieldKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> label;

    @Column(name = "value_type", nullable = false, length = 30)
    private String valueType;

    @Column(name = "is_required", nullable = false)
    private boolean required;

    @Column(name = "is_critical", nullable = false)
    private boolean critical;

    /** Pipe separated label variants; {@code null} means a human fills this field in. */
    @Column(name = "extraction_hint")
    private String extractionHint;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 100;

    protected DocumentTypeField() {
        // for JPA
    }

    public UUID getId() {
        return id;
    }

    public UUID getDocumentTypeId() {
        return documentTypeId;
    }

    public String getFieldKey() {
        return fieldKey;
    }

    public Map<String, Object> getLabel() {
        return label;
    }

    public String labelIn(String language) {
        if (label == null) {
            return fieldKey;
        }
        Object localized = label.get(language);
        if (localized == null) {
            localized = label.get("de");
        }
        return localized == null ? fieldKey : String.valueOf(localized);
    }

    public String getValueType() {
        return valueType;
    }

    public boolean isRequired() {
        return required;
    }

    public boolean isCritical() {
        return critical;
    }

    public String getExtractionHint() {
        return extractionHint;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    /** Label variants the extractor searches for, in the order they are written. */
    public java.util.List<String> labels() {
        if (extractionHint == null || extractionHint.isBlank()) {
            return java.util.List.of();
        }
        return java.util.Arrays.stream(extractionHint.split("\\|"))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .toList();
    }
}
