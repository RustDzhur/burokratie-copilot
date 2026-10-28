package de.buerokratiecopilot.approvals.domain;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Which payments need which approval (spec §15, §31).
 *
 * <p>A rule is configuration, because who may release how much money is a company's decision: an
 * amount above which two people must agree, a role that may approve at all, and how many levels are
 * required. The platform ships the mechanism and applies the rule it is given.</p>
 *
 * <p>{@code minAmount} and {@code maxAmount} bound the rule's range; a payment is matched against every
 * active rule and the strictest applicable one wins, so a narrow rule can never be bypassed by adding
 * another.</p>
 */
@Entity
@Table(name = "payment_approval_rules")
public class PaymentApprovalRule {

    @Id
    private UUID id;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(nullable = false, length = 80)
    private String code;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private java.util.Map<String, Object> name;

    @Column(name = "min_amount", precision = 14, scale = 2)
    private BigDecimal minAmount;

    @Column(name = "max_amount", precision = 14, scale = 2)
    private BigDecimal maxAmount;

    @Column(nullable = false, length = 3)
    private String currency = "EUR";

    @Column(length = 20)
    private String direction;

    @Column(name = "required_role", length = 80)
    private String requiredRole;

    @Column(name = "required_permission", length = 120)
    private String requiredPermission;

    /** How many levels of approval the payment needs. */
    @Column(name = "approval_levels", nullable = false)
    private int approvalLevels = 1;

    /** Whether two different people must decide, which is the four-eyes principle. */
    @Column(name = "require_second_person", nullable = false)
    private boolean requireSecondPerson;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private int version = 1;

    protected PaymentApprovalRule() {
        // for JPA
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

    public java.util.Map<String, Object> getName() {
        return name;
    }

    /** The name in a language, falling back to German and then to the code. */
    public String nameIn(String language) {
        if (name == null) {
            return code;
        }
        Object localized = name.get(language);
        if (localized == null) {
            localized = name.get("de");
        }
        return localized == null ? code : String.valueOf(localized);
    }

    public BigDecimal getMinAmount() {
        return minAmount;
    }

    public BigDecimal getMaxAmount() {
        return maxAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getDirection() {
        return direction;
    }

    public String getRequiredRole() {
        return requiredRole;
    }

    public String getRequiredPermission() {
        return requiredPermission;
    }

    public int getApprovalLevels() {
        return approvalLevels;
    }

    public boolean isRequireSecondPerson() {
        return requireSecondPerson;
    }

    public boolean isActive() {
        return active;
    }

    /** Whether this rule covers a payment of that amount in that currency and direction. */
    public boolean appliesTo(BigDecimal amount, String paymentCurrency, String paymentDirection) {
        if (!active || amount == null) {
            return false;
        }
        if (currency != null && paymentCurrency != null && !currency.equalsIgnoreCase(paymentCurrency)) {
            return false;
        }
        if (direction != null && paymentDirection != null && !direction.equalsIgnoreCase(paymentDirection)) {
            return false;
        }
        if (minAmount != null && amount.compareTo(minAmount) < 0) {
            return false;
        }
        return maxAmount == null || amount.compareTo(maxAmount) <= 0;
    }
}
