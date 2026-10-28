package de.buerokratiecopilot.expenses.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * One category of the expense catalogue (ELSTER/DATEV style).
 *
 * <p>The catalogue is data, not an enum: a trade adds a category, a tax adviser corrects a DATEV account and
 * a business renames a folder without a release. The parent link makes it a tree; the {@code code} is the
 * stable identifier that documents and rules carry, so renaming a category never separates a receipt from
 * the drawer it lies in.</p>
 *
 * <p>{@code isBusinessExpense == false} means the category must not be posted as a business expense —
 * the private withdrawal. Such a receipt is still filed (so it is not lost), but the warning travels with
 * the category to every screen that shows it.</p>
 */
@Entity
@Table(name = "expense_categories")
public class ExpenseCategory {

    @Id
    private UUID id;

    @Column(nullable = false, length = 120)
    private String code;

    @Column(name = "parent_id")
    private UUID parentId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> description = Map.of();

    /** DATEV base account of SKR03 (process-oriented chart). */
    @Column(name = "datev_skr03", length = 10)
    private String datevSkr03;

    /** DATEV base account of SKR04 (balance-sheet-oriented chart). */
    @Column(name = "datev_skr04", length = 10)
    private String datevSkr04;

    /** Folder template below the archive root, with {year} and {month} resolved when a document is filed. */
    @Column(name = "folder_path", length = 400)
    private String folderPath;

    /**
     * EÜR line (Anlage EÜR) this category belongs to. A category without a line is a collection node:
     * a document filed there would disappear from the report, so it must not be auto-filed.
     */
    @Column(name = "euer_line", length = 20)
    private String euerLine;

    @Column(name = "euer_kz", length = 40)
    private String euerKz;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 100;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "is_business_expense", nullable = false)
    private boolean businessExpense = true;

    @Column(name = "requires_review", nullable = false)
    private boolean requiresReview;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> warning = Map.of();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ExpenseCategory() {
        // for JPA
    }

    /**
     * Creates a category. The factory is the only way in, so a test builds a category exactly the way the
     * administrator's endpoint does and an invalid code cannot reach the table.
     */
    public static ExpenseCategory of(String code, UUID parentId, Map<String, String> name,
                                     Map<String, String> description, String datevSkr03, String datevSkr04,
                                     String folderPath, int sortOrder, boolean businessExpense,
                                     boolean requiresReview, Map<String, String> warning) {
        String normalized = requireCode(code);
        if (name == null || name.get("de") == null || String.valueOf(name.get("de")).isBlank()) {
            throw new IllegalArgumentException("An expense category needs a German name");
        }
        ExpenseCategory category = new ExpenseCategory();
        category.id = UUID.randomUUID();
        category.code = normalized;
        category.parentId = parentId;
        category.name = Map.copyOf(name);
        category.description = description == null ? Map.of() : Map.copyOf(description);
        category.datevSkr03 = blankToNull(datevSkr03);
        category.datevSkr04 = blankToNull(datevSkr04);
        category.folderPath = normalizeFolder(folderPath);
        category.sortOrder = sortOrder;
        category.businessExpense = businessExpense;
        category.requiresReview = requiresReview;
        category.warning = warning == null ? Map.of() : Map.copyOf(warning);
        return category;
    }

    public static String requireCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("An expense category needs a code");
        }
        String normalized = code.strip().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("^[a-z0-9]+([._-][a-z0-9]+)*$") || normalized.length() > 120) {
            throw new IllegalArgumentException(
                    "A category code consists of lower-case letters, digits and single dots, dashes or underscores");
        }
        return normalized;
    }

    /** A blank folder is no folder; a relative one is made absolute so every document path has the same shape. */
    public static String normalizeFolder(String folder) {
        if (folder == null || folder.isBlank()) {
            return null;
        }
        String cleaned = folder.strip();
        if (!cleaned.startsWith("/")) {
            cleaned = "/" + cleaned;
        }
        return cleaned.length() > 400 ? null : cleaned;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public UUID getParentId() {
        return parentId;
    }

    public void setParentId(UUID parentId) {
        this.parentId = parentId;
    }

    public Map<String, Object> getName() {
        return name;
    }

    public void setName(Map<String, Object> name) {
        this.name = name;
    }

    public Map<String, Object> getDescription() {
        return description;
    }

    public void setDescription(Map<String, Object> description) {
        this.description = description == null ? Map.of() : description;
    }

    public String getDatevSkr03() {
        return datevSkr03;
    }

    public void setDatevSkr03(String datevSkr03) {
        this.datevSkr03 = blankToNull(datevSkr03);
    }

    public String getDatevSkr04() {
        return datevSkr04;
    }

    public void setDatevSkr04(String datevSkr04) {
        this.datevSkr04 = blankToNull(datevSkr04);
    }

    public String getFolderPath() {
        return folderPath;
    }

    public String getEuerLine() {
        return euerLine;
    }

    public void setEuerLine(String euerLine) {
        this.euerLine = euerLine;
    }

    public String getEuerKz() {
        return euerKz;
    }

    public void setEuerKz(String euerKz) {
        this.euerKz = euerKz;
    }

    public void setFolderPath(String folderPath) {
        this.folderPath = normalizeFolder(folderPath);
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isBusinessExpense() {
        return businessExpense;
    }

    public void setBusinessExpense(boolean businessExpense) {
        this.businessExpense = businessExpense;
    }

    public boolean isRequiresReview() {
        return requiresReview;
    }

    public void setRequiresReview(boolean requiresReview) {
        this.requiresReview = requiresReview;
    }

    public Map<String, Object> getWarning() {
        return warning;
    }

    public void setWarning(Map<String, Object> warning) {
        this.warning = warning == null ? Map.of() : warning;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** Localized value with German as fallback, then the code so a screen never shows nothing. */
    public String nameIn(String language) {
        return localized(name, language, code);
    }

    public String descriptionIn(String language) {
        return localized(description, language, "");
    }

    public String warningIn(String language) {
        return localized(warning, language, "");
    }

    private static String localized(Map<String, Object> values, String language, String fallback) {
        if (values == null) {
            return fallback;
        }
        Object value = values.get(language);
        if (value == null) {
            value = values.get("de");
        }
        return value == null ? fallback : String.valueOf(value);
    }
}
