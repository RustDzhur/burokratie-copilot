package de.buerokratiecopilot.invoices.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One line item of an invoice (spec §14).
 *
 * <p>Lines exist so the totals can be checked. An invoice stored without them can be paid but not
 * verified, and the acceptance criterion of D-003 — that a discrepancy is marked — requires the
 * arithmetic to be recomputable.</p>
 */
@Entity
@Table(name = "invoice_lines")
public class InvoiceLine {

    @Id
    private UUID id;

    @Column(name = "invoice_id", nullable = false)
    private UUID invoiceId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "line_number", nullable = false)
    private int lineNumber;

    @Column(nullable = false)
    private String description;

    @Column(precision = 14, scale = 4)
    private BigDecimal quantity;

    private String unit;

    @Column(name = "unit_price", precision = 14, scale = 4)
    private BigDecimal unitPrice;

    @Column(name = "discount_percent", precision = 5, scale = 2)
    private BigDecimal discountPercent;

    @Column(name = "net_amount", precision = 14, scale = 2)
    private BigDecimal netAmount;

    @Column(name = "tax_rate_percent", precision = 5, scale = 2)
    private BigDecimal taxRatePercent;

    @Column(name = "tax_amount", precision = 14, scale = 2)
    private BigDecimal taxAmount;

    @Column(name = "gross_amount", precision = 14, scale = 2)
    private BigDecimal grossAmount;

    @Column(name = "cost_category")
    private String costCategory;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> metadata = Map.of();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected InvoiceLine() {
        // for JPA
    }

    public InvoiceLine(UUID id, UUID tenantId, UUID invoiceId, int lineNumber, String description) {
        this.id = id;
        this.tenantId = tenantId;
        this.invoiceId = invoiceId;
        this.lineNumber = lineNumber;
        this.description = description;
    }

    public UUID getId() {
        return id;
    }

    public UUID getInvoiceId() {
        return invoiceId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    public BigDecimal getDiscountPercent() {
        return discountPercent;
    }

    public void setDiscountPercent(BigDecimal discountPercent) {
        this.discountPercent = discountPercent;
    }

    public BigDecimal getNetAmount() {
        return netAmount;
    }

    public void setNetAmount(BigDecimal netAmount) {
        this.netAmount = netAmount;
    }

    public BigDecimal getTaxRatePercent() {
        return taxRatePercent;
    }

    public void setTaxRatePercent(BigDecimal taxRatePercent) {
        this.taxRatePercent = taxRatePercent;
    }

    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public void setTaxAmount(BigDecimal taxAmount) {
        this.taxAmount = taxAmount;
    }

    public BigDecimal getGrossAmount() {
        return grossAmount;
    }

    public void setGrossAmount(BigDecimal grossAmount) {
        this.grossAmount = grossAmount;
    }

    public String getCostCategory() {
        return costCategory;
    }

    public void setCostCategory(String costCategory) {
        this.costCategory = costCategory;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
