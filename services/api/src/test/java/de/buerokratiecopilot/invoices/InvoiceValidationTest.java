package de.buerokratiecopilot.invoices;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.invoices.InvoiceValidation.Flag;
import de.buerokratiecopilot.invoices.InvoiceValidation.Result;
import de.buerokratiecopilot.invoices.InvoiceValidation.Severity;
import de.buerokratiecopilot.invoices.InvoiceValidation.Subject;

/**
 * Verifies invoice validation (spec §14, D-003 acceptance).
 *
 * <p>The criterion is that a discrepancy between the line items and the stated total is marked. The
 * cases below are that, plus the ones that decide whether the mark is worth anything: no false alarm on
 * rounding, no repair of the paper, and a distinction between "this is wrong" and "nobody has looked at
 * this yet".</p>
 */
class InvoiceValidationTest {

    private static Subject.Line line(int number, String net, String tax, String gross) {
        return new Subject.Line(number, decimal(net), decimal(tax), decimal(gross), "Position " + number);
    }

    /**
     * A complete invoice, including a bank connection: an invoice without one is a legitimate case and
     * has its own test, so it must not make every other case look incomplete.
     */
    private static Subject invoice(List<Subject.Line> lines, String net, String tax, String gross) {
        return new Subject("RE-2026-001", "EUR", decimal(net), decimal(tax), decimal(gross), lines,
                "DE89 3704 0044 0532 0130 00", "DE89370400440532013000", true,
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), "Muster GmbH");
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    @Test
    @DisplayName("an invoice whose lines add up is accepted without flags")
    void consistentInvoicePasses() {
        Result result = InvoiceValidation.validate(
                invoice(List.of(line(1, "100.00", "19.00", "119.00"), line(2, "50.00", "9.50", "59.50")),
                        "150.00", "28.50", "178.50"),
                null);

        assertThat(result.state()).isEqualTo("OK");
        assertThat(result.flags()).isEmpty();
        assertThat(result.blocksAutomaticPosting()).isFalse();
        assertThat(result.computedTotals()).containsEntry("grossFromLines", new BigDecimal("178.50"));
    }

    @Test
    @DisplayName("a total that disagrees with the lines is flagged as blocking")
    void totalMismatchIsFlagged() {
        Result result = InvoiceValidation.validate(
                invoice(List.of(line(1, "100.00", "19.00", "119.00")), "100.00", "19.00", "129.00"),
                null);

        assertThat(result.state()).isEqualTo("BLOCKED");
        assertThat(result.blocksAutomaticPosting()).isTrue();
        assertThat(result.blocking()).extracting(Flag::code).contains("TOTAL_MISMATCH_GROSS");

        Flag flag = result.blocking().getFirst();
        assertThat(flag.details())
                .as("a person has to see both numbers to judge the difference")
                .containsEntry("fromLines", "119.00")
                .containsEntry("stated", "129.00")
                .containsEntry("difference", "10.00");
    }

    @Test
    @DisplayName("the platform reports the discrepancy but does not repair the invoice")
    void theInvoiceIsNotCorrected() {
        Result result = InvoiceValidation.validate(
                invoice(List.of(line(1, "100.00", "19.00", "119.00")), "100.00", "19.00", "129.00"),
                null);

        // The computed total is reported next to the stated one; nothing overwrites what the paper says,
        // because the paper is what gets paid.
        assertThat(result.computedTotals()).containsEntry("grossFromLines", new BigDecimal("119.00"));
        assertThat(result.blocking().getFirst().message()).contains("129.00");
    }

    @Test
    @DisplayName("rounding to the cent is not reported as a discrepancy")
    void roundingIsNotADiscrepancy() {
        Result result = InvoiceValidation.validate(
                invoice(List.of(line(1, "33.333", "6.334", "39.667")), "33.33", "6.33", "39.67"), null);

        assertThat(result.flags()).isEmpty();
        assertThat(result.state()).isEqualTo("OK");
    }

    @Test
    @DisplayName("a tolerance set by the tenant is honoured")
    void toleranceIsConfigurable() {
        Result strict = InvoiceValidation.validate(
                invoice(List.of(line(1, "100.00", "0", "100.00")), "100.00", "0", "100.50"), null);
        Result lenient = InvoiceValidation.validate(
                invoice(List.of(line(1, "100.00", "0", "100.00")), "100.00", "0", "100.50"),
                new BigDecimal("1.00"));

        assertThat(strict.state()).isEqualTo("BLOCKED");
        assertThat(lenient.state()).isEqualTo("OK");
    }

    @Test
    @DisplayName("an invoice without line items warns instead of pretending the sums are right")
    void noLinesIsAWarning() {
        Result result = InvoiceValidation.validate(invoice(List.of(), "100.00", "19.00", "119.00"), null);

        assertThat(result.state()).isEqualTo("NEEDS_REVIEW");
        assertThat(result.flags()).extracting(Flag::code).contains("NO_LINE_ITEMS");
        assertThat(result.flags().getFirst().severity()).isEqualTo(Severity.WARNING);
    }

    @Test
    @DisplayName("missing mandatory data blocks the invoice")
    void missingMandatoryDataBlocks() {
        Result withoutNumber = InvoiceValidation.validate(new Subject("  ", "EUR", decimal("100.00"),
                decimal("19.00"), decimal("119.00"), List.of(), null, null, false, null, null, null), null);
        Result withoutCurrency = InvoiceValidation.validate(new Subject("RE-1", null, decimal("100.00"),
                decimal("19.00"), decimal("119.00"), List.of(), null, null, false, null, null, null), null);
        Result withoutAmounts = InvoiceValidation.validate(new Subject("RE-1", "EUR", null, null, null,
                List.of(), null, null, false, null, null, null), null);

        assertThat(withoutNumber.blocking()).extracting(Flag::code).contains("MISSING_INVOICE_NUMBER");
        assertThat(withoutCurrency.blocking()).extracting(Flag::code).contains("MISSING_CURRENCY");
        assertThat(withoutAmounts.blocking()).extracting(Flag::code).contains("MISSING_TOTAL");
    }

    @Test
    @DisplayName("a due date before the issue date is worth a look but does not block")
    void dueDateBeforeIssueIsAWarning() {
        Result result = InvoiceValidation.validate(new Subject("RE-1", "EUR", decimal("100.00"), null,
                decimal("119.00"), List.of(line(1, "100.00", null, "119.00")), null, null, false,
                LocalDate.of(2026, 3, 31), LocalDate.of(2026, 3, 1), "Muster GmbH"), null);

        assertThat(result.state()).isEqualTo("NEEDS_REVIEW");
        assertThat(result.flags()).extracting(Flag::code).contains("DUE_BEFORE_ISSUE");
        assertThat(result.blocksAutomaticPosting()).isFalse();
    }

    @Test
    @DisplayName("a supplier seen for the first time is not treated as suspicious")
    void firstIbanIsNotSuspicious() {
        Result result = InvoiceValidation.validate(new Subject("RE-1", "EUR", decimal("100.00"), null,
                decimal("100.00"), List.of(line(1, "100.00", null, "100.00")),
                "DE89 3704 0044 0532 0130 00", null, false, LocalDate.of(2026, 3, 1), null,
                "Muster GmbH"), null);

        assertThat(result.flags()).extracting(Flag::code).contains("IBAN_FIRST_SEEN");
        assertThat(result.flags().getFirst().severity())
                .as("inventing suspicion where there is nothing to compare would train users to ignore "
                        + "the warning that matters")
                .isEqualTo(Severity.INFO);
        assertThat(result.state()).isEqualTo("OK");
    }

    @Test
    @DisplayName("a changed bank connection blocks automatic posting")
    void changedIbanBlocks() {
        Result result = InvoiceValidation.validate(new Subject("RE-1", "EUR", decimal("100.00"), null,
                decimal("100.00"), List.of(line(1, "100.00", null, "100.00")),
                "DE89 3704 0044 0532 0130 00", "DE12 5001 0517 0648 4898 90", true,
                LocalDate.of(2026, 3, 1), null, "Muster GmbH"), null);

        assertThat(result.state()).isEqualTo("BLOCKED");
        assertThat(result.blocking()).extracting(Flag::code).contains("IBAN_CHANGED");
        Flag flag = result.blocking().getFirst();
        assertThat(flag.details())
                .containsEntry("ibanOnInvoice", "DE89370400440532013000")
                .containsEntry("knownIban", "DE12500105170648489890");
    }

    @Test
    @DisplayName("the same account written differently is not a change")
    void formattingDoesNotMakeAChange() {
        Result result = InvoiceValidation.validate(new Subject("RE-1", "EUR", decimal("100.00"), null,
                decimal("100.00"), List.of(line(1, "100.00", null, "100.00")),
                "de89 3704 0044 0532 0130 00", "DE89370400440532013000", true,
                LocalDate.of(2026, 3, 1), null, "Muster GmbH"), null);

        assertThat(result.flags())
                .as("spaces and case are how banks print an IBAN, not a change of bank")
                .isEmpty();
    }

    @Test
    @DisplayName("an invoice without an IBAN is a warning, because it may legitimately be paid in cash")
    void missingIbanIsAWarning() {
        Result result = InvoiceValidation.validate(new Subject("RE-1", "EUR", decimal("100.00"), null,
                decimal("100.00"), List.of(line(1, "100.00", null, "100.00")), null, null, false,
                LocalDate.of(2026, 3, 1), null, "Muster GmbH"), null);

        assertThat(result.state()).isEqualTo("NEEDS_REVIEW");
        assertThat(result.flags()).extracting(Flag::code).contains("MISSING_IBAN");
    }

    @Test
    @DisplayName("the state is one word, and the flags are machine readable")
    void stateIsUsableByTheInterface() {
        Result result = InvoiceValidation.validate(
                invoice(List.of(line(1, "100.00", "19.00", "119.00")), "100.00", "19.00", "129.00"),
                null);

        assertThat(result.asState())
                .containsEntry("state", "BLOCKED")
                .containsEntry("blocksAutomaticPosting", true);
        assertThat(result.asState().get("flags")).isInstanceOf(List.class);
    }
}
