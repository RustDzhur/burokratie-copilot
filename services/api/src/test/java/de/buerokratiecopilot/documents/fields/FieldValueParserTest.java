package de.buerokratiecopilot.documents.fields;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies the value conversion of extracted fields (spec §9.4, §10).
 *
 * <p>A wrong conversion is worse than a missing value, because it looks like a fact: an amount read
 * as 1190.0 instead of 1.190,00 would be off by a factor of a thousand, and a date corrected from
 * 31.02. into 03.03. would silently move a deadline. The cases below are the formats German documents
 * actually use, plus the ones that must be refused.</p>
 */
class FieldValueParserTest {

    @ParameterizedTest(name = "{0} is read as {1}")
    @CsvSource({
        "'1.190,00', 1190.00",
        "'119,00', 119.00",
        "'1,190.00', 1190.00",
        "'1190.00', 1190.00",
        "'1.190', 1190.00",
        "'0,99', 0.99",
        "'1.234.567,89', 1234567.89",
        "'-119,00', -119.00",
        "'12', 12.00"
    })
    @DisplayName("German and English amounts are both read correctly")
    void parsesAmounts(String raw, String expected) {
        FieldValueParser.Parsed parsed = FieldValueParser.parse(raw, "AMOUNT");

        assertThat(parsed.valid()).isTrue();
        assertThat(parsed.normalized()).isEqualTo(expected);
    }

    @Test
    @DisplayName("a currency symbol does not confuse the amount")
    void parsesAmountWithCurrency() {
        assertThat(FieldValueParser.parse("1.190,00 €", "AMOUNT").normalized()).isEqualTo("1190.00");
        assertThat(FieldValueParser.parse("EUR 119,00", "AMOUNT").normalized()).isEqualTo("119.00");
        assertThat(FieldValueParser.parse("119,00 EUR", "AMOUNT").normalized()).isEqualTo("119.00");
    }

    @ParameterizedTest(name = "{0} becomes {1}")
    @CsvSource({
        "'31.03.2026', 2026-03-31",
        "'31.3.26', 2026-03-31",
        "'2026-03-31', 2026-03-31",
        "'1. Januar 2026', 2026-01-01",
        "'31. März 2026', 2026-03-31",
        "'03.04.2026', 2026-04-03"
    })
    @DisplayName("German and ISO dates become ISO, day first")
    void parsesDates(String raw, String expected) {
        assertThat(FieldValueParser.parse(raw, "DATE").normalized()).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
        "'01.11.2025 bis 31.12.2025', 2025-11-01/2025-12-31",
        "'vom 01.11.2025 - 31.12.2025', 2025-11-01/2025-12-31",
        "'2025-11-01 bis 2025-12-31', 2025-11-01/2025-12-31",
        "'24 Monate', 24"
    })
    @DisplayName("two dates are a period kept as an ISO interval; a duration stays months")
    void parsesPeriods(String raw, String expected) {
        assertThat(FieldValueParser.parse(raw, "PERIOD").normalized()).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = { "31.02.2026", "32.01.2026", "00.00.2026", "kein Datum", "2026-13-01" })
    @DisplayName("a date that does not exist is refused, not rolled over")
    void refusesImpossibleDates(String raw) {
        assertThat(FieldValueParser.parse(raw, "DATE").valid()).isFalse();
    }

    @Test
    @DisplayName("an IBAN is normalised and its checksum verified")
    void parsesIban() {
        FieldValueParser.Parsed parsed = FieldValueParser.parse("DE89 3704 0044 0532 0130 00", "IBAN");

        assertThat(parsed.valid()).isTrue();
        assertThat(parsed.normalized()).isEqualTo("DE89370400440532013000");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "DE89 3704 0044 0532 0130 01",   // one digit wrong
        "DE89 3704 0044 0532 0130 0",    // too short
        "89370400440532013000",          // no country code
        "DEAA 3704 0044 0532 0130 00"    // letters where digits belong
    })
    @DisplayName("an IBAN with a wrong checksum is refused, which catches transposed digits")
    void refusesBrokenIban(String raw) {
        assertThat(FieldValueParser.parse(raw, "IBAN").valid()).isFalse();
    }

    @Test
    @DisplayName("durations are converted to months only when the unit is exact")
    void parsesPeriods() {
        assertThat(FieldValueParser.parse("24 Monate", "PERIOD").normalized()).isEqualTo("24");
        assertThat(FieldValueParser.parse("3 Monate", "PERIOD").normalized()).isEqualTo("3");
        assertThat(FieldValueParser.parse("2 Jahre", "PERIOD").normalized()).isEqualTo("24");
        assertThat(FieldValueParser.parse("1 Jahr", "PERIOD").normalized()).isEqualTo("12");

        // Weeks and days have no exact month equivalent: the original text is kept rather than a
        // rounded number that would shift a deadline.
        assertThat(FieldValueParser.parse("2 Wochen", "PERIOD").normalized()).isEqualTo("2 Wochen");
    }

    @Test
    @DisplayName("currency symbols and codes both resolve to an ISO code")
    void parsesCurrencies() {
        assertThat(FieldValueParser.parse("€", "CURRENCY").normalized()).isEqualTo("EUR");
        assertThat(FieldValueParser.parse("EUR", "CURRENCY").normalized()).isEqualTo("EUR");
        assertThat(FieldValueParser.parse("Betrag in USD", "CURRENCY").normalized()).isEqualTo("USD");
        assertThat(FieldValueParser.parse("Rubel", "CURRENCY").valid()).isFalse();
    }

    @Test
    @DisplayName("BICs are accepted in both lengths and rejected otherwise")
    void parsesBic() {
        assertThat(FieldValueParser.parse("COBADEFF", "BIC").normalized()).isEqualTo("COBADEFF");
        assertThat(FieldValueParser.parse("COBADEFFXXX", "BIC").normalized()).isEqualTo("COBADEFFXXX");
        assertThat(FieldValueParser.parse("COBADE", "BIC").valid()).isFalse();
    }

    @Test
    @DisplayName("text fields keep their value, and an empty one is not a value")
    void parsesText() {
        assertThat(FieldValueParser.parse("  Muster GmbH  ", "TEXT").normalized()).isEqualTo("Muster GmbH");
        assertThat(FieldValueParser.parse("", "TEXT").valid()).isFalse();
        // An unknown type is kept as it is rather than guessed at.
        assertThat(FieldValueParser.parse("Wert", "SOMETHING_NEW").normalized()).isEqualTo("Wert");
    }

    @Test
    @DisplayName("numbers lose trailing zeros, amounts keep two decimals")
    void distinguishesNumbersFromAmounts() {
        assertThat(FieldValueParser.parse("1.190,00", "NUMBER").normalized()).isEqualTo("1190");
        assertThat(FieldValueParser.parse("1.190,00", "AMOUNT").normalized()).isEqualTo("1190.00");
        assertThat(FieldValueParser.parse("0,50", "NUMBER").normalized()).isEqualTo("0.5");
    }

    @Test
    @DisplayName("checkable types are marked as such, free text is not")
    void marksVerifiableTypes() {
        assertThat(FieldValueParser.isVerifiable("AMOUNT")).isTrue();
        assertThat(FieldValueParser.isVerifiable("IBAN")).isTrue();
        assertThat(FieldValueParser.isVerifiable("TEXT")).isFalse();
        assertThat(FieldValueParser.isVerifiable(null)).isFalse();
    }
}
