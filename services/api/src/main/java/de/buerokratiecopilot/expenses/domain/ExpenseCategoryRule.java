package de.buerokratiecopilot.expenses.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * One piece of evidence for an expense category.
 *
 * <p>The rule types are deliberately different in kind, because expense receipts are recognised differently
 * from letters: a shop is named in the sender line (OBI, Würth, Shell), a tool is named in the position text,
 * and the size of a purchase decides between a consumable and an investment. A single weight cannot express
 * all three, so a rule says what it looks at.</p>
 *
 * <p>Rules are platform data. A business that buys from a supplier nobody knew adds a sender rule through
 * the API; the next document is filed correctly without a release.</p>
 */
@Entity
@Table(name = "expense_category_rules")
public class ExpenseCategoryRule {

    public enum RuleType {
        /** Case-insensitive substring of the extracted text (German and Russian synonyms are both allowed). */
        KEYWORD,
        /** Regular expression for a pattern a keyword cannot express. */
        REGEX,
        /** Substring of the sender line or sender e-mail; identifies the shop, not the kind of purchase. */
        SENDER,
        /** Part of the file name, for scans that are named after the receipt. */
        FILENAME,
        /** Any amount on the document at or above {@code amount_min}. */
        AMOUNT_AT_LEAST,
        /** Any amount on the document at or below {@code amount_max}. */
        AMOUNT_AT_MOST,
        /** Any amount between {@code amount_min} and {@code amount_max}, both inclusive. */
        AMOUNT_BETWEEN
    }

    @Id
    private UUID id;

    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false, length = 30)
    private RuleType ruleType = RuleType.KEYWORD;

    @Column
    private String pattern;

    @Column(name = "amount_min", precision = 12, scale = 2)
    private BigDecimal amountMin;

    @Column(name = "amount_max", precision = 12, scale = 2)
    private BigDecimal amountMax;

    @Column(nullable = false, precision = 4, scale = 3)
    private BigDecimal weight = BigDecimal.valueOf(0.5);

    /** Used to break a tie between two equally strong categories. */
    @Column(nullable = false)
    private int priority = 100;

    /** Terms that must all appear in the text for the rule to count; empty means none are required. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "requires_all", columnDefinition = "jsonb", nullable = false)
    private List<String> requiresAll = List.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> description = Map.of();

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ExpenseCategoryRule() {
        // for JPA
    }

    /**
     * Creates a rule. A factory instead of a public constructor keeps the identity under the control of the
     * domain and makes every test build a rule the same way the API does.
     */
    public static ExpenseCategoryRule of(UUID categoryId, RuleType ruleType, String pattern,
                                         BigDecimal amountMin, BigDecimal amountMax, double weight,
                                         int priority, List<String> requiresAll,
                                         Map<String, String> description) {
        if (categoryId == null) {
            throw new IllegalArgumentException("A rule needs a category");
        }
        RuleType type = ruleType == null ? RuleType.KEYWORD : ruleType;
        String cleaned = pattern == null ? null : pattern.strip();
        boolean amountRule = type == RuleType.AMOUNT_AT_LEAST || type == RuleType.AMOUNT_AT_MOST
                || type == RuleType.AMOUNT_BETWEEN;
        if (!amountRule && (cleaned == null || cleaned.length() < 2)) {
            throw new IllegalArgumentException("A " + type + " rule needs a pattern of at least 2 characters");
        }
        if (type == RuleType.AMOUNT_AT_LEAST && amountMin == null) {
            throw new IllegalArgumentException("AMOUNT_AT_LEAST needs a lower bound");
        }
        if (type == RuleType.AMOUNT_AT_MOST && amountMax == null) {
            throw new IllegalArgumentException("AMOUNT_AT_MOST needs an upper bound");
        }
        if (type == RuleType.AMOUNT_BETWEEN && (amountMin == null || amountMax == null)) {
            throw new IllegalArgumentException("AMOUNT_BETWEEN needs both bounds");
        }
        if ((amountMin != null && amountMin.signum() < 0) || (amountMax != null && amountMax.signum() < 0)) {
            throw new IllegalArgumentException("Amount thresholds cannot be negative");
        }
        if (amountMin != null && amountMax != null && amountMin.compareTo(amountMax) > 0) {
            throw new IllegalArgumentException("The lower bound is above the upper bound");
        }
        if (weight <= 0 || weight > 1) {
            throw new IllegalArgumentException("A rule weight must be within (0, 1]");
        }

        ExpenseCategoryRule rule = new ExpenseCategoryRule();
        rule.id = UUID.randomUUID();
        rule.categoryId = categoryId;
        rule.ruleType = type;
        rule.pattern = cleaned;
        rule.amountMin = amountMin;
        rule.amountMax = amountMax;
        rule.weight = BigDecimal.valueOf(weight);
        rule.priority = priority;
        rule.requiresAll = requiresAll == null ? List.of() : List.copyOf(requiresAll);
        rule.description = description == null ? Map.of() : Map.copyOf(description);
        return rule;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(UUID categoryId) {
        this.categoryId = categoryId;
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
        this.pattern = pattern == null ? null : pattern.strip();
    }

    public BigDecimal getAmountMin() {
        return amountMin;
    }

    public void setAmountMin(BigDecimal amountMin) {
        this.amountMin = amountMin;
    }

    public BigDecimal getAmountMax() {
        return amountMax;
    }

    public void setAmountMax(BigDecimal amountMax) {
        this.amountMax = amountMax;
    }

    public BigDecimal getWeight() {
        return weight;
    }

    public void setWeight(BigDecimal weight) {
        this.weight = weight;
    }

    public double weightAsDouble() {
        return weight == null ? 0.5 : weight.doubleValue();
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public List<String> getRequiresAll() {
        return requiresAll == null ? List.of() : requiresAll;
    }

    public void setRequiresAll(List<String> requiresAll) {
        this.requiresAll = requiresAll == null ? List.of() : List.copyOf(requiresAll);
    }

    public Map<String, Object> getDescription() {
        return description;
    }

    public void setDescription(Map<String, Object> description) {
        this.description = description == null ? Map.of() : description;
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
}
