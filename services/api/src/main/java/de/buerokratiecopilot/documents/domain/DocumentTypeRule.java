package de.buerokratiecopilot.documents.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One piece of evidence the classifier uses (spec §9.3, §31).
 *
 * <p>A rule is configuration: "this pattern is a hint for this type, this strongly". Keeping them in
 * the database is what allows a customer to fix a misclassification themselves — a hard-coded list of
 * keywords would make every correction a release.</p>
 */
@Entity
@Table(name = "document_type_rules")
public class DocumentTypeRule {

    public enum RuleType {
        /** Case-insensitive substring of the document text. */
        KEYWORD,
        /** Regular expression, for patterns a keyword cannot express. */
        REGEX,
        /** Domain of the sender, for mail ingestion. */
        SENDER_DOMAIN,
        /** Part of the file name. */
        FILENAME
    }

    @Id
    private UUID id;

    @Column(name = "document_type_id", nullable = false)
    private UUID documentTypeId;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false, length = 20)
    private RuleType ruleType = RuleType.KEYWORD;

    @Column(nullable = false, length = 300)
    private String pattern;

    @Column(nullable = false, precision = 4, scale = 3)
    private java.math.BigDecimal weight = java.math.BigDecimal.valueOf(0.5);

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected DocumentTypeRule() {
        // for JPA
    }

    /**
     * Creates a rule. A factory instead of a public constructor keeps the entity's identity fields
     * under the control of the domain: an administrator adds a rule through this method, and a test
     * builds one the same way the application does.
     */
    public static DocumentTypeRule of(java.util.UUID documentTypeId, java.util.UUID tenantId,
                                      RuleType ruleType, String pattern, double weight) {
        if (pattern == null || pattern.strip().length() < 2) {
            throw new IllegalArgumentException("A classification rule needs a pattern of at least 2 characters");
        }
        if (weight <= 0 || weight > 1) {
            throw new IllegalArgumentException("A rule weight must be within (0, 1]");
        }
        DocumentTypeRule rule = new DocumentTypeRule();
        rule.id = java.util.UUID.randomUUID();
        rule.documentTypeId = documentTypeId;
        rule.tenantId = tenantId;
        rule.ruleType = ruleType == null ? RuleType.KEYWORD : ruleType;
        rule.pattern = pattern.strip();
        rule.weight = java.math.BigDecimal.valueOf(weight);
        return rule;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDocumentTypeId() {
        return documentTypeId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public RuleType getRuleType() {
        return ruleType;
    }

    public void setRuleType(RuleType ruleType) {
        this.ruleType = ruleType;
    }

    public String getPattern() {
        return pattern;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    public java.math.BigDecimal getWeight() {
        return weight;
    }

    public void setWeight(java.math.BigDecimal weight) {
        this.weight = weight;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public double weightAsDouble() {
        return weight == null ? 0.5 : weight.doubleValue();
    }
}
