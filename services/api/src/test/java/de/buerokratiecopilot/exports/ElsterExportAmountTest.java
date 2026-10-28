package de.buerokratiecopilot.exports;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The amount reader behind the export: German receipts write {@code 1.234,56 €}, invoices often a plain decimal. */
class ElsterExportAmountTest {

    @Test
    @DisplayName("reads the German and the plain decimal form, including currency and thousands separators")
    void readsGermanAndPlainAmounts() {
        assertThat(ElsterExportService.parseAmount("1.234,56 €")).isEqualByComparingTo("1234.56");
        assertThat(ElsterExportService.parseAmount("119,00 EUR")).isEqualByComparingTo("119.00");
        assertThat(ElsterExportService.parseAmount("119.00")).isEqualByComparingTo("119.00");
        assertThat(ElsterExportService.parseAmount("-42,10")).isEqualByComparingTo("-42.10");
        assertThat(ElsterExportService.parseAmount("0,00")).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("an unreadable or missing amount is null, never zero and never an exception")
    void unreadableAmountIsNull() {
        assertThat(ElsterExportService.parseAmount(null)).isNull();
        assertThat(ElsterExportService.parseAmount("  ")).isNull();
        assertThat(ElsterExportService.parseAmount("keine Angabe")).isNull();
        assertThat(ElsterExportService.parseAmount("-")).isNull();
    }
}
