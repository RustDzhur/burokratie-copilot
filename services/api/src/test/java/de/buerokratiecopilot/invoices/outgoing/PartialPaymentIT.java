package de.buerokratiecopilot.invoices.outgoing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.invoices.InvoiceRepository;
import de.buerokratiecopilot.invoices.domain.Invoice;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * Verifies partial payments (spec §14, D-006 acceptance).
 *
 * <p>A partial payment is the normal case, and the criterion is that it is reflected correctly. What
 * "correctly" means is tested from both sides: the amounts add up, and the state the invoice ends up in
 * matches the money — including the cases that are easy to get wrong, such as paying more than was
 * owed, and a payment that was only prepared and never executed.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class PartialPaymentIT {

    @Autowired
    private PartialPaymentService partialPayments;

    @Autowired
    private InvoiceRepository invoices;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID tenant() {
        return jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'Teilzahlung Test', 'COMPANY') returning id
                """, UUID.class, "pay-" + UUID.randomUUID().toString().substring(0, 12));
    }

    /** An invoice with a gross amount, stored. */
    private Invoice invoice(UUID tenantId, String gross) {
        Invoice invoice = new Invoice(UUID.randomUUID(), tenantId,
                "RE-" + UUID.randomUUID().toString().substring(0, 8));
        invoice.setCurrency("EUR");
        invoice.setNetAmount(new BigDecimal(gross));
        invoice.setGrossAmount(new BigDecimal(gross));
        return RequestContext.runAs(tenantId, () -> invoices.save(invoice));
    }

    /**
     * An executed payment, created the way the platform creates one.
     *
     * <p>The first version of this helper wrote the row by hand, and the schema refused it for a reason
     * I could not explain from the constraint text alone. Going through the production path removes the
     * question and tests that path as well — which is what the test should have done from the start.</p>
     */
    private UUID executedPayment(UUID tenantId, UUID invoiceId, String amount, String currency) {
        UUID confirmer = person(tenantId);
        return RequestContext.runAs(tenantId, () -> partialPayments.recordExternalPayment(tenantId,
                invoiceId, new BigDecimal(amount), currency, LocalDate.of(2026, 3, 15), "Kontoauszug",
                confirmer));
    }

    /** A person, because an executed payment has to name who confirmed it. */
    private UUID person(UUID tenantId) {
        return jdbc.queryForObject("""
                insert into users (id, email, status, display_name, locale, time_zone,
                                   failed_login_attempts, must_change_password)
                values (gen_random_uuid(), ?, 'ACTIVE', 'Zahlung Prüfer', 'de', 'Europe/Berlin', 0, false)
                returning id
                """, UUID.class, "pp-" + UUID.randomUUID().toString().substring(0, 10) + "@example.de");
    }

    /** A payment that was prepared but not executed, created through the approval service. */
    private UUID preparedPayment(UUID tenantId, UUID invoiceId, String amount) {
        return RequestContext.runAs(tenantId, () -> {
            var prepared = approvals.prepare(new de.buerokratiecopilot.approvals.PaymentApprovalService
                    .PrepareRequest(tenantId, invoiceId, null, new BigDecimal(amount), "EUR",
                    "Muster GmbH", null, null, LocalDate.of(2026, 3, 15)), null);
            return prepared.payment().getId();
        });
    }

    @Autowired
    private de.buerokratiecopilot.approvals.PaymentApprovalService approvals;

    @Test
    @DisplayName("a partial payment leaves the invoice partly paid, with the rest still open")
    void partialPaymentLeavesTheRestOpen() {
        UUID tenantId = tenant();
        Invoice invoice = invoice(tenantId, "119.00");
        UUID paymentId = executedPayment(tenantId, invoice.getId(), "50.00", "EUR");

        PartialPaymentService.Allocation allocation = RequestContext.runAs(tenantId,
                () -> partialPayments.apply(tenantId, invoice.getId(), paymentId));

        assertThat(allocation.paidBefore()).isEqualByComparingTo("0");
        assertThat(allocation.paidAfter()).isEqualByComparingTo("50.00");
        assertThat(allocation.outstanding()).isEqualByComparingTo("69.00");
        assertThat(allocation.paymentStatus()).isEqualTo(Invoice.PaymentStatus.PARTIALLY_PAID);
        assertThat(allocation.invoice().getStatus()).isEqualTo(Invoice.Status.PARTIALLY_PAID);
    }

    @Test
    @DisplayName("a second partial payment adds up and can settle the invoice")
    void paymentsAddUp() {
        UUID tenantId = tenant();
        Invoice invoice = invoice(tenantId, "119.00");

        RequestContext.runAs(tenantId, () -> partialPayments.apply(tenantId, invoice.getId(),
                executedPayment(tenantId, invoice.getId(), "50.00", "EUR")));
        PartialPaymentService.Allocation second = RequestContext.runAs(tenantId,
                () -> partialPayments.apply(tenantId, invoice.getId(),
                        executedPayment(tenantId, invoice.getId(), "69.00", "EUR")));

        assertThat(second.paidAfter()).isEqualByComparingTo("119.00");
        assertThat(second.outstanding()).isEqualByComparingTo("0");
        assertThat(second.paymentStatus()).isEqualTo(Invoice.PaymentStatus.PAID);
        assertThat(second.invoice().getStatus()).isEqualTo(Invoice.Status.PAID);
    }

    @Test
    @DisplayName("an overpayment is recorded as such rather than as fully paid")
    void overpaymentIsRecorded() {
        UUID tenantId = tenant();
        Invoice invoice = invoice(tenantId, "119.00");

        PartialPaymentService.Allocation allocation = RequestContext.runAs(tenantId,
                () -> partialPayments.apply(tenantId, invoice.getId(),
                        executedPayment(tenantId, invoice.getId(), "150.00", "EUR")));

        assertThat(allocation.paymentStatus())
                .as("a credit the customer will ask about is not the same as a settled invoice")
                .isEqualTo(Invoice.PaymentStatus.OVERPAID);
        assertThat(allocation.outstanding()).isEqualByComparingTo("-31.00");
    }

    @Test
    @DisplayName("a payment that was only prepared does not change what has been paid")
    void unexecutedPaymentDoesNotCount() {
        UUID tenantId = tenant();
        Invoice invoice = invoice(tenantId, "119.00");
        UUID prepared = preparedPayment(tenantId, invoice.getId(), "119.00");

        assertThatThrownBy(() -> RequestContext.runAs(tenantId,
                () -> partialPayments.apply(tenantId, invoice.getId(), prepared)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("ausgeführte Zahlung");

        Invoice unchanged = RequestContext.runAs(tenantId,
                () -> invoices.findById(invoice.getId()).orElseThrow());
        assertThat(unchanged.getPaidAmount()).isEqualByComparingTo("0");
        assertThat(unchanged.getPaymentStatus()).isEqualTo(Invoice.PaymentStatus.UNPAID);
    }

    @Test
    @DisplayName("a payment in another currency is refused, because the platform does not convert")
    void foreignCurrencyIsRefused() {
        UUID tenantId = tenant();
        Invoice invoice = invoice(tenantId, "119.00");
        UUID paymentId = executedPayment(tenantId, invoice.getId(), "119.00", "CHF");

        assertThatThrownBy(() -> RequestContext.runAs(tenantId,
                () -> partialPayments.apply(tenantId, invoice.getId(), paymentId)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("CHF");
    }

    @Test
    @DisplayName("a cancelled invoice takes no payment")
    void cancelledInvoiceTakesNoPayment() {
        UUID tenantId = tenant();
        Invoice invoice = invoice(tenantId, "119.00");
        UUID paymentId = executedPayment(tenantId, invoice.getId(), "50.00", "EUR");

        RequestContext.runAs(tenantId, () -> {
            Invoice stored = invoices.findById(invoice.getId()).orElseThrow();
            stored.setStatus(Invoice.Status.CANCELLED);
            return invoices.save(stored);
        });

        assertThatThrownBy(() -> RequestContext.runAs(tenantId,
                () -> partialPayments.apply(tenantId, invoice.getId(), paymentId)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("stornierte");
    }

    @Test
    @DisplayName("the payment state follows the amounts, so it cannot contradict them")
    void stateFollowsTheAmounts() {
        assertThat(PartialPaymentService.statusFor(new BigDecimal("0"), new BigDecimal("119.00")))
                .isEqualTo(Invoice.PaymentStatus.UNPAID);
        assertThat(PartialPaymentService.statusFor(new BigDecimal("50.00"), new BigDecimal("119.00")))
                .isEqualTo(Invoice.PaymentStatus.PARTIALLY_PAID);
        assertThat(PartialPaymentService.statusFor(new BigDecimal("119.00"), new BigDecimal("119.00")))
                .isEqualTo(Invoice.PaymentStatus.PAID);
        assertThat(PartialPaymentService.statusFor(new BigDecimal("120.00"), new BigDecimal("119.00")))
                .isEqualTo(Invoice.PaymentStatus.OVERPAID);
    }

    @Test
    @DisplayName("an unpaid invoice keeps a more informative status than 'unpaid'")
    void unpaidInvoiceKeepsItsStatus() {
        assertThat(PartialPaymentService.statusForInvoice(Invoice.PaymentStatus.UNPAID))
                .as("'in review' or 'scheduled' says more than 'unpaid', which is true of almost everything")
                .isNull();
        assertThat(PartialPaymentService.statusForInvoice(Invoice.PaymentStatus.PAID))
                .isEqualTo(Invoice.Status.PAID);
    }

    @Test
    @DisplayName("a payment recorded outside the platform can be entered and applied")
    void externalPaymentCanBeRecorded() {
        UUID tenantId = tenant();
        Invoice invoice = invoice(tenantId, "119.00");

        UUID paymentId = RequestContext.runAs(tenantId, () -> partialPayments.recordExternalPayment(
                tenantId, invoice.getId(), new BigDecimal("119.00"), "EUR", LocalDate.of(2026, 3, 15),
                "Überweisung laut Kontoauszug", person(tenantId)));

        PartialPaymentService.Allocation allocation = RequestContext.runAs(tenantId,
                () -> partialPayments.apply(tenantId, invoice.getId(), paymentId));

        assertThat(allocation.paymentStatus()).isEqualTo(Invoice.PaymentStatus.PAID);
        assertThat(RequestContext.runAs(tenantId,
                () -> partialPayments.outstanding(tenantId, invoice.getId())))
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a negative or zero amount cannot be recorded as a payment")
    void nonPositiveAmountIsRefused() {
        UUID tenantId = tenant();
        Invoice invoice = invoice(tenantId, "119.00");

        assertThatThrownBy(() -> RequestContext.runAs(tenantId, () -> partialPayments.recordExternalPayment(
                tenantId, invoice.getId(), BigDecimal.ZERO, "EUR", LocalDate.of(2026, 3, 15), null,
                person(tenantId))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("positiv");
    }

    @Test
    @DisplayName("an executed payment without a confirming person is refused, with a clear reason")
    void executedPaymentNeedsAConfirmer() {
        UUID tenantId = tenant();
        Invoice invoice = invoice(tenantId, "119.00");

        // The schema says the same thing, but its message names a constraint; this one names the rule.
        assertThatThrownBy(() -> RequestContext.runAs(tenantId, () -> partialPayments.recordExternalPayment(
                tenantId, invoice.getId(), new BigDecimal("50.00"), "EUR", LocalDate.of(2026, 3, 15),
                null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("bestätigt");
    }
}
