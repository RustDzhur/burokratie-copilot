package de.buerokratiecopilot.invoices.outgoing;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A numbering sequence for outgoing invoices (spec §14, D-006).
 *
 * <p>The counter lives in a table rather than in a database sequence, and that is the point: a
 * PostgreSQL sequence does not roll back, so a failed invoice would leave a gap in the numbering. An
 * invoice number with a gap is not a cosmetic problem — a tax auditor asks about missing numbers, and
 * "the transaction failed" is an answer the platform should make unnecessary.</p>
 *
 * <p>The pattern decides the shape ({@code RE-{year}-{number:5}}), the reset policy decides when the
 * counter starts over, and both are configuration: a company's numbering is its own convention.</p>
 */
@Entity
@Table(name = "invoice_number_sequences")
public class InvoiceNumberSequence {

    public enum ResetPolicy {
        NEVER, YEARLY, MONTHLY
    }

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 40)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    /** Placeholders: {@code {year}}, {@code {month}}, {@code {number}}, {@code {number:N}}. */
    @Column(nullable = false, length = 120)
    private String pattern;

    @Enumerated(EnumType.STRING)
    @Column(name = "reset_policy", nullable = false, length = 20)
    private ResetPolicy resetPolicy = ResetPolicy.YEARLY;

    @Column(name = "next_value", nullable = false)
    private long nextValue = 1;

    /** The year the counter currently belongs to, when the policy resets yearly. */
    private Integer year;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected InvoiceNumberSequence() {
        // for JPA
    }

    public InvoiceNumberSequence(UUID id, UUID tenantId, String code, String name, String pattern) {
        this.id = id;
        this.tenantId = tenantId;
        this.code = code;
        this.name = name;
        this.pattern = pattern;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getPattern() {
        return pattern;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    public ResetPolicy getResetPolicy() {
        return resetPolicy;
    }

    public void setResetPolicy(ResetPolicy resetPolicy) {
        this.resetPolicy = resetPolicy;
    }

    public long getNextValue() {
        return nextValue;
    }

    public Integer getYear() {
        return year;
    }

    public void setYear(Integer year) {
        this.year = year;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Applies the reset policy and returns the number to use.
     *
     * <p>The counter is advanced here and nowhere else; whether the advance survives depends on the
     * transaction that called it, which is what keeps the numbering free of gaps.</p>
     */
    public long takeNext(java.time.LocalDate today) {
        if (resetPolicy == ResetPolicy.YEARLY && (year == null || year != today.getYear())) {
            nextValue = 1;
            year = today.getYear();
        }
        else if (resetPolicy == ResetPolicy.MONTHLY && (year == null
                || year != today.getYear() * 100 + today.getMonthValue())) {
            nextValue = 1;
            year = today.getYear() * 100 + today.getMonthValue();
        }
        long taken = nextValue;
        nextValue = taken + 1;
        updatedAt = Instant.now();
        return taken;
    }

    /** The number as it will appear on the invoice. */
    public String format(long number, java.time.LocalDate today) {
        String rendered = pattern
                .replace("{year}", String.valueOf(today.getYear()))
                .replace("{month}", String.format("%02d", today.getMonthValue()));

        java.util.regex.Matcher padded = java.util.regex.Pattern
                .compile("\\{number:(\\d+)}").matcher(rendered);
        if (padded.find()) {
            int width = Integer.parseInt(padded.group(1));
            rendered = padded.replaceFirst(java.util.regex.Matcher.quoteReplacement(
                    String.format("%0" + width + "d", number)));
        }
        return rendered.replace("{number}", String.valueOf(number));
    }
}
