package de.buerokratiecopilot.invoices.outgoing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.approvals.domain.Payment;
import de.buerokratiecopilot.invoices.InvoiceRepository;
import de.buerokratiecopilot.invoices.domain.Invoice;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Records payments against an invoice (spec §14, D-006).
 *
 * <p>Partial payments are the normal case in business, and the interesting part is not the arithmetic
 * but the state it leaves behind: an invoice is unpaid, partly paid, paid or overpaid, and each of those
 * means something different to whoever looks at the list. The platform derives that state from the
 * amounts rather than letting a caller set it, because a status that can be set independently of the
 * money is a status that will eventually be wrong.</p>
 *
 * <p>{@code paidAmount} is only ever moved by this service, and only by the amount of a payment that
 * was actually executed. A payment that was merely prepared does not change what has been paid.</p>
 */
@Service
public class PartialPaymentService {

    private static final Logger log = LoggerFactory.getLogger(PartialPaymentService.class);

    private final InvoiceRepository invoices;
    private final JdbcTemplate jdbc;

    public PartialPaymentService(InvoiceRepository invoices, JdbcTemplate jdbc) {
        this.invoices = invoices;
        this.jdbc = jdbc;
    }

    /** What the invoice looks like after a payment was recorded. */
    public record Allocation(Invoice invoice, BigDecimal paidBefore, BigDecimal paidAfter,
                             BigDecimal outstanding, Invoice.PaymentStatus paymentStatus) {

        public Map<String, Object> asDiagnostics() {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("paidBefore", paidBefore.toPlainString());
            details.put("paidAfter", paidAfter.toPlainString());
            details.put("outstanding", outstanding.toPlainString());
            details.put("paymentStatus", paymentStatus.name());
            return details;
        }
    }

    /**
     * Applies a payment to an invoice.
     *
     * <p>Refused when the payment was not executed: money that has not moved cannot have arrived.
     * Refused as well when the invoice is cancelled or rejected, because a cancelled invoice with a
     * balance is a contradiction.</p>
     */
    @Transactional
    public Allocation apply(UUID tenantId, UUID invoiceId, UUID paymentId) {
        Invoice invoice = invoices.findByIdAndTenantIdAndDeletedAtIsNull(invoiceId, tenantId)
                .orElseThrow(() -> ApiException.notFound("invoice"));

        Map<String, Object> payment = jdbc.queryForMap("""
                select amount, currency, status from payments where id = ? and tenant_id = ?
                """, paymentId, tenantId);

        String status = String.valueOf(payment.get("status"));
        if (!"EXECUTED".equals(status) && !"SETTLED".equals(status)) {
            throw ApiException.conflict(
                    "Nur eine ausgeführte Zahlung kann auf eine Rechnung angerechnet werden; diese ist "
                            + status);
        }

        BigDecimal amount = (BigDecimal) payment.get("amount");
        String currency = String.valueOf(payment.get("currency"));
        if (!invoice.getCurrency().equalsIgnoreCase(currency)) {
            // Adding amounts in different currencies would produce a number that means nothing.
            throw ApiException.conflict("Die Zahlung ist in " + currency + ", die Rechnung in "
                    + invoice.getCurrency() + "; eine Umrechnung nimmt die Plattform nicht vor");
        }
        if (invoice.getStatus() == Invoice.Status.CANCELLED
                || invoice.getStatus() == Invoice.Status.REJECTED) {
            throw ApiException.conflict("Eine stornierte oder abgelehnte Rechnung nimmt keine Zahlung an");
        }

        BigDecimal before = invoice.getPaidAmount() == null ? BigDecimal.ZERO : invoice.getPaidAmount();
        BigDecimal after = before.add(amount);
        invoice.setPaidAmount(after);

        Invoice.PaymentStatus paymentStatus = statusFor(after, invoice.getGrossAmount());
        invoice.setPaymentStatus(paymentStatus);
        Invoice.Status newStatus = statusForInvoice(paymentStatus);
        if (newStatus != null) {
            invoice.setStatus(newStatus);
        }

        Invoice saved = invoices.save(invoice);
        BigDecimal outstanding = invoice.getGrossAmount() == null ? BigDecimal.ZERO
                : invoice.getGrossAmount().subtract(after);

        log.info("Payment {} applied to invoice {}: {} -> {} ({})", paymentId, invoiceId, before, after,
                paymentStatus);
        return new Allocation(saved, before, after, outstanding, paymentStatus);
    }

    /** Derives the payment state from the amounts, so it cannot disagree with them. */
    static Invoice.PaymentStatus statusFor(BigDecimal paid, BigDecimal gross) {
        if (gross == null || gross.signum() == 0) {
            return Invoice.PaymentStatus.NOT_APPLICABLE;
        }
        int comparison = paid.compareTo(gross);
        if (comparison < 0) {
            return paid.signum() == 0 ? Invoice.PaymentStatus.UNPAID
                    : Invoice.PaymentStatus.PARTIALLY_PAID;
        }
        return comparison == 0 ? Invoice.PaymentStatus.PAID : Invoice.PaymentStatus.OVERPAID;
    }

    /** The invoice's own status follows the money, except where it says more than the money does. */
    static Invoice.Status statusForInvoice(Invoice.PaymentStatus paymentStatus) {
        return switch (paymentStatus) {
            case PAID -> Invoice.Status.PAID;
            case OVERPAID -> Invoice.Status.PAID;
            case PARTIALLY_PAID -> Invoice.Status.PARTIALLY_PAID;
            // An unpaid invoice keeps whatever state it had: waiting for approval or scheduled is more
            // informative than "unpaid", which is already true of almost everything.
            case UNPAID, NOT_APPLICABLE -> null;
        };
    }

    /** What is still open on an invoice. */
    @Transactional(readOnly = true)
    public BigDecimal outstanding(UUID tenantId, UUID invoiceId) {
        Invoice invoice = invoices.findByIdAndTenantIdAndDeletedAtIsNull(invoiceId, tenantId)
                .orElseThrow(() -> ApiException.notFound("invoice"));
        BigDecimal gross = invoice.getGrossAmount() == null ? BigDecimal.ZERO : invoice.getGrossAmount();
        BigDecimal paid = invoice.getPaidAmount() == null ? BigDecimal.ZERO : invoice.getPaidAmount();
        return gross.subtract(paid);
    }

    /**
     * Records a payment that was executed outside the platform, with its date.
     *
     * <p>A confirming person is required, and the schema enforces it too: an executed payment must name
     * who confirmed it. That is the same rule as in D-004 — no payment without a person — and this path
     * originally allowed {@code null}, which produced an opaque constraint violation instead of a clear
     * refusal. A test found it.</p>
     */
    @Transactional
    public UUID recordExternalPayment(UUID tenantId, UUID invoiceId, BigDecimal amount, String currency,
                                      LocalDate paymentDate, String reference, UUID userId) {
        if (amount == null || amount.signum() <= 0) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Ein Zahlungsbetrag muss positiv sein");
        }
        if (userId == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Eine ausgeführte Zahlung muss festhalten, wer sie bestätigt hat");
        }
        UUID paymentId = jdbc.queryForObject("""
                insert into payments (id, tenant_id, invoice_id, direction, amount, currency, status,
                                      payment_date, reference, confirmed_by, confirmed_at, executed_by,
                                      executed_at, metadata, created_by)
                values (gen_random_uuid(), ?, ?, 'INCOMING', ?, ?, 'EXECUTED', ?, ?, ?, now(), ?, now(),
                        '{}'::jsonb, ?)
                returning id
                """, UUID.class, tenantId, invoiceId, amount,
                currency == null ? "EUR" : currency.toUpperCase(java.util.Locale.ROOT), paymentDate,
                reference, userId, userId, userId);
        return paymentId;
    }
}
