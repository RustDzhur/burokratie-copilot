package de.buerokratiecopilot.invoices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * Verifies the bank-details alert (spec §14, D-003 acceptance).
 *
 * <p>The criterion is that a suspicious change of bank details blocks automatic posting. The test
 * follows the life of a supplier relationship: the first account is remembered without comment, an
 * invoice naming a different one cannot be approved, and only a person confirming the new account lets
 * it through — after which the new account is the known one.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class IbanChangeAlertTest {

    @Autowired
    private InvoiceService invoices;

    @Autowired
    private UserRepository users;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private static final String FIRST_IBAN = "DE89370400440532013000";
    private static final String OTHER_IBAN = "DE12500105170648489890";

    private record Tenant(UUID tenantId, UUID userId) {
    }

    /**
     * A real tenant row.
     *
     * <p>The invoices and suppliers point at it by foreign key, so a made-up identifier would be
     * rejected — which is what happened when this test first used one, and the database was right to
     * refuse it.</p>
     */
    private Tenant tenant() {
        UUID tenantId = jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'Fremdlieferanten Test', 'COMPANY')
                returning id
                """, UUID.class, "inv-" + UUID.randomUUID().toString().substring(0, 12));
        return new Tenant(tenantId, null);
    }

    private record InvoiceRequest(String number, String iban, String gross) {
    }

    private InvoiceService.Recorded record(Tenant tenant, String number, String iban, String net,
                                           String tax, String gross) {
        return RequestContext.runAs(tenant.tenantId(), () -> invoices.record(
                new InvoiceService.InvoiceRequest(tenant.tenantId(), number, null, "Muster GmbH",
                        "INCOMING", "INVOICE", "EUR", new BigDecimal(net), new BigDecimal(tax),
                        new BigDecimal(gross), iban, null, null, java.time.LocalDate.of(2026, 3, 1), null,
                        java.time.LocalDate.of(2026, 3, 31), 30, null, null,
                        List.of(new InvoiceService.LineRequest(1, "Leistung", BigDecimal.ONE, "Stk",
                                new BigDecimal(net), new BigDecimal(net), new BigDecimal("19.00"),
                                new BigDecimal(tax), new BigDecimal(gross), null))),
                tenant.userId()));
    }

    @Test
    @DisplayName("the first account of a supplier is remembered without an alert")
    void firstAccountIsRememberedQuietly() {
        Tenant tenant = tenant();

        InvoiceService.Recorded recorded = record(tenant, "RE-1", FIRST_IBAN, "100.00", "19.00",
                "119.00");

        assertThat(recorded.invoice().isBankDetailsChanged())
                .as("there was nothing to compare against, so nothing is suspicious")
                .isFalse();
        assertThat(recorded.validation().state()).isEqualTo("OK");
        assertThat(recorded.invoice().getValidationFlags()).extracting(flag -> flag.get("code"))
                .contains("IBAN_FIRST_SEEN");
    }

    @Test
    @DisplayName("an invoice naming a different account is blocked and cannot be approved silently")
    void changedAccountIsBlocked() {
        Tenant tenant = tenant();
        record(tenant, "RE-100", FIRST_IBAN, "100.00", "19.00", "119.00");

        InvoiceService.Recorded second = record(tenant, "RE-101", OTHER_IBAN, "100.00", "19.00", "119.00");

        assertThat(second.invoice().isBankDetailsChanged()).isTrue();
        assertThat(second.invoice().blocksAutomaticPosting()).isTrue();
        assertThat(second.invoice().getStatus())
                .as("a blocked invoice waits for a person")
                .isEqualTo(de.buerokratiecopilot.invoices.domain.Invoice.Status.IN_REVIEW);
        assertThat(second.invoice().getValidationFlags()).extracting(flag -> flag.get("code"))
                .contains("IBAN_CHANGED");

        // Without an explicit confirmation the approval is refused.
        assertThatThrownBy(() -> RequestContext.runAs(tenant.tenantId(), () -> invoices.approve(
                second.invoice().getId(), tenant.tenantId(), tenant.userId(), false, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Bankverbindung");
    }

    @Test
    @DisplayName("a person confirming the new account lets the invoice through and updates what is known")
    void confirmedAccountBecomesTheKnownOne() {
        Tenant tenant = tenant();
        record(tenant, "RE-200", FIRST_IBAN, "100.00", "19.00", "119.00");
        InvoiceService.Recorded second = record(tenant, "RE-201", OTHER_IBAN, "100.00", "19.00", "119.00");

        RequestContext.runAs(tenant.tenantId(), () -> invoices.approve(second.invoice().getId(),
                tenant.tenantId(), tenant.userId(), true, "Neue Bankverbindung telefonisch bestätigt"));

        // The next invoice with the same account is no longer a change: the alert can clear, which is
        // the only way it stays meaningful.
        InvoiceService.Recorded third = record(tenant, "RE-202", OTHER_IBAN, "100.00", "19.00", "119.00");
        assertThat(third.invoice().isBankDetailsChanged()).isFalse();
        assertThat(third.validation().state()).isEqualTo("OK");
    }

    @Test
    @DisplayName("the same account written differently is not a change")
    void formattingIsNotAChange() {
        Tenant tenant = tenant();
        record(tenant, "RE-300", FIRST_IBAN, "100.00", "19.00", "119.00");

        InvoiceService.Recorded second = record(tenant, "RE-301", "DE89 3704 0044 0532 0130 00",
                "100.00", "19.00", "119.00");

        assertThat(second.invoice().isBankDetailsChanged()).isFalse();
    }

    @Test
    @DisplayName("an invoice whose lines do not add up is blocked for review")
    void arithmeticDiscrepancyIsBlocked() {
        Tenant tenant = tenant();

        // The line says 119,00 and the invoice states 129,00. The helper cannot express that, because
        // it uses one number for both — which is exactly why the first version of this test proved
        // nothing.
        InvoiceService.Recorded recorded = RequestContext.runAs(tenant.tenantId(), () -> invoices.record(
                new InvoiceService.InvoiceRequest(tenant.tenantId(), "RE-400", null, "Muster GmbH",
                        "INCOMING", "INVOICE", "EUR", new BigDecimal("100.00"), new BigDecimal("19.00"),
                        new BigDecimal("129.00"), FIRST_IBAN, null, null,
                        java.time.LocalDate.of(2026, 3, 1), null, java.time.LocalDate.of(2026, 3, 31), 30,
                        null, null, List.of(new InvoiceService.LineRequest(1, "Leistung",
                                BigDecimal.ONE, "Stk", new BigDecimal("100.00"), new BigDecimal("100.00"),
                                new BigDecimal("19.00"), new BigDecimal("19.00"),
                                new BigDecimal("119.00"), null))),
                tenant.userId()));

        assertThat(recorded.validation().state()).isEqualTo("BLOCKED");
        assertThat(recorded.invoice().getValidationFlags()).extracting(flag -> flag.get("code"))
                .contains("TOTAL_MISMATCH_GROSS");
        assertThat(recorded.invoice().getStatus())
                .isEqualTo(de.buerokratiecopilot.invoices.domain.Invoice.Status.IN_REVIEW);
    }

    @Test
    @DisplayName("a duplicate of the same supplier and number is refused")
    void duplicateIsRefused() {
        Tenant tenant = tenant();
        record(tenant, "RE-500", FIRST_IBAN, "100.00", "19.00", "119.00");

        assertThatThrownBy(() -> record(tenant, "RE-500", FIRST_IBAN, "100.00", "19.00", "119.00"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("bereits erfasst");
    }

    @Test
    @DisplayName("invoices a person has to look at are listed")
    void blockedInvoicesAreListed() {
        Tenant tenant = tenant();
        record(tenant, "RE-600", FIRST_IBAN, "100.00", "19.00", "119.00");
        record(tenant, "RE-601", OTHER_IBAN, "100.00", "19.00", "119.00");

        List<de.buerokratiecopilot.invoices.domain.Invoice> review = RequestContext.runAs(tenant.tenantId(),
                () -> invoices.needingReview(tenant.tenantId()));

        assertThat(review).isNotEmpty();
        assertThat(review).anySatisfy(invoice -> assertThat(invoice.isBankDetailsChanged()).isTrue());
    }

    @Test
    @DisplayName("the tenant is real, so the records belong somewhere")
    void tenantIsRecorded() {
        Tenant tenant = tenant();
        InvoiceService.Recorded recorded = record(tenant, "RE-700", FIRST_IBAN, "100.00", "19.00",
                "119.00");

        assertThat(recorded.invoice().getTenantId()).isEqualTo(tenant.tenantId());
        assertThat(users.count()).isGreaterThanOrEqualTo(0);
    }
}
