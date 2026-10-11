package de.buerokratiecopilot.invoices.domain;

import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A supplier, with the bank connection the platform has seen from them (spec §14).
 *
 * <p>{@code knownIban} is the point of this record. A supplier's account changing between letters is
 * the commonest form of invoice fraud, and the only way to notice is to remember what it used to be.
 * The first account seen is remembered without comment; a later one that differs is what the alert is
 * for.</p>
 */
@Entity
@Table(name = "suppliers")
public class Supplier {

    @Id
    private UUID id;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "contact_id")
    private UUID contactId;

    @Column(nullable = false, length = 300)
    private String name;

    @Column(name = "supplier_number", length = 60)
    private String supplierNumber;

    @Column(name = "vat_id", length = 40)
    private String vatId;

    @Column(name = "tax_number", length = 40)
    private String taxNumber;

    @Column(name = "default_payment_terms")
    private Integer defaultPaymentTerms;

    @Column(name = "default_currency", nullable = false, length = 3)
    private String defaultCurrency = "EUR";

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    /**
     * The account the supplier was last paid into. The column set has no dedicated place for it, so it
     * lives in the metadata the schema provides — the alternative would be inventing a table for one
     * value.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> metadata = Map.of();

    protected Supplier() {
        // for JPA
    }

    public Supplier(UUID id, UUID tenantId, String name) {
        this.id = id;
        this.tenantId = tenantId;
        this.name = name;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getContactId() {
        return contactId;
    }

    public void setContactId(UUID contactId) {
        this.contactId = contactId;
    }

    public String getName() {
        return name;
    }

    public String getSupplierNumber() {
        return supplierNumber;
    }

    public void setSupplierNumber(String supplierNumber) {
        this.supplierNumber = supplierNumber;
    }

    public String getVatId() {
        return vatId;
    }

    public void setVatId(String vatId) {
        this.vatId = vatId;
    }

    public String getTaxNumber() {
        return taxNumber;
    }

    public void setTaxNumber(String taxNumber) {
        this.taxNumber = taxNumber;
    }

    public boolean isActive() {
        return active;
    }

    public String getDefaultCurrency() {
        return defaultCurrency;
    }

    public void setDefaultCurrency(String defaultCurrency) {
        this.defaultCurrency = defaultCurrency;
    }

    public Integer getDefaultPaymentTerms() {
        return defaultPaymentTerms;
    }

    public void setDefaultPaymentTerms(Integer defaultPaymentTerms) {
        this.defaultPaymentTerms = defaultPaymentTerms;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /** The account last seen from this supplier, or {@code null} when none was recorded yet. */
    public String knownIban() {
        Object value = metadata.get("knownIban");
        return value == null ? null : String.valueOf(value);
    }

    public boolean hasKnownIban() {
        return knownIban() != null && !knownIban().isBlank();
    }

    /** Remembers an account, replacing nothing else. */
    public void rememberIban(String iban) {
        Map<String, Object> updated = new java.util.HashMap<>(metadata);
        updated.put("knownIban", iban == null ? null : iban.replaceAll("\\s", "")
                .toUpperCase(java.util.Locale.ROOT));
        updated.put("knownIbanSeenAt", java.time.Instant.now().toString());
        this.metadata = Map.copyOf(updated);
    }
}
