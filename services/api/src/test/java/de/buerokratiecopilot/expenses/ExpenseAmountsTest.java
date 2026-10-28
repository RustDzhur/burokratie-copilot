package de.buerokratiecopilot.expenses;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** How the German amount format and the folder template are read. */
class ExpenseAmountsTest {

    @Test
    @DisplayName("a German amount with thousands separator is read as one number")
    void readsGermanThousandsSeparator() {
        assertThat(ExpenseAmounts.extract("Rechnungsbetrag 1.234,56 €"))
                .containsExactly(new BigDecimal("1234.56"));
    }

    @Test
    @DisplayName("the currency may stand before the number")
    void readsCurrencyPrefix() {
        assertThat(ExpenseAmounts.extract("EUR 89,90"))
                .containsExactly(new BigDecimal("89.90"));
    }

    @Test
    @DisplayName("millions are read without losing a digit")
    void readsMillions() {
        assertThat(ExpenseAmounts.extract("Kaufpreis 1.234.567,89 EUR"))
                .containsExactly(new BigDecimal("1234567.89"));
    }

    @Test
    @DisplayName("a date or document number is not an amount")
    void datesAndNumbersWithoutCurrencyAreIgnored() {
        assertThat(ExpenseAmounts.extract("Rechnung Nr. 20.10.2026, Lieferung am 21.10.2026")).isEmpty();
    }

    @Test
    @DisplayName("an integer amount is read with two decimals")
    void readsWholeEuroAmounts() {
        assertThat(ExpenseAmounts.extract("Summe 1.000 €"))
                .containsExactly(new BigDecimal("1000.00"));
    }

    @Test
    @DisplayName("one decimal is completed, not truncated")
    void completesOneDecimal() {
        assertThat(ExpenseAmounts.extract("1,5 €")).containsExactly(new BigDecimal("1.50"));
    }

    @Test
    @DisplayName("a repeated total is returned once")
    void removesDuplicates() {
        assertThat(ExpenseAmounts.extract("19,99 € ... Gesamt 19,99 EUR"))
                .containsExactly(new BigDecimal("19.99"));
    }

    @Test
    @DisplayName("the highest amount is what the amount rules use")
    void highestPicksTheLargest() {
        assertThat(ExpenseAmounts.highest("Position 10,00 €, Position 900,00 €, Gesamt 910,00 €"))
                .isEqualByComparingTo("910.00");
    }

    @Test
    @DisplayName("text without currency has no amount")
    void noCurrencyNoAmount() {
        assertThat(ExpenseAmounts.extract("Betrag: 1234,56")).isEmpty();
    }

    @Test
    @DisplayName("several amounts keep their order")
    void keepsOrder() {
        assertThat(ExpenseAmounts.extract("10,00 € und 20,00 € und 30,00 €"))
                .containsExactly(new BigDecimal("10.00"), new BigDecimal("20.00"), new BigDecimal("30.00"));
    }

    @Test
    @DisplayName("the folder template resolves year and month from the document date")
    void resolvesFolderTemplate() {
        assertThat(ExpenseCategorizationService.resolveFolder("/Belege/{year}/Material/{month}/Waren",
                LocalDate.of(2026, 3, 15), null))
                .isEqualTo("/Belege/2026/Material/03/Waren");
    }

    @Test
    @DisplayName("a category without a template creates no folder")
    void noTemplateNoFolder() {
        assertThat(ExpenseCategorizationService.resolveFolder(null, LocalDate.of(2026, 3, 15), null)).isNull();
        assertThat(ExpenseCategorizationService.resolveFolder("  ", LocalDate.of(2026, 3, 15), null)).isNull();
    }

    @Test
    @DisplayName("without a document date the receipt date decides the year")
    void fallsBackToReceivedAt() {
        String folder = ExpenseCategorizationService.resolveFolder("/Belege/{year}/Material",
                null, Instant.parse("2026-05-01T10:00:00Z"));
        assertThat(folder).matches("/Belege/\\d{4}/Material");
    }

    @Test
    @DisplayName("a folder template without placeholders is used verbatim")
    void verbatimTemplate() {
        assertThat(ExpenseCategorizationService.resolveFolder("/Belege/Sammel", LocalDate.of(2026, 1, 1), null))
                .isEqualTo("/Belege/Sammel");
    }

    @Test
    @DisplayName("the amount list is unmodifiable so a caller cannot alter the evidence")
    void resultIsReadOnly() {
        List<BigDecimal> amounts = ExpenseAmounts.extract("10,00 €");
        assertThat(amounts).isUnmodifiable();
    }
}
