package de.buerokratiecopilot.exports;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The bytes of the provisional DATEV file (§5.6, §12.1).
 *
 * <p>These assertions are the contract of the file itself: UTF-8 with BOM (so Excel opens it without a manual
 * import), a semicolon separator, the warning in the first line, and a header row with exactly the nine
 * columns. They also pin the escaping, because a supplier containing a semicolon would otherwise shift the
 * booking row and produce a file that opens and is wrong.</p>
 */
class DatevCsvWriterTest {

    private static final DatevCsvWriter.Row ROW = new DatevCsvWriter.Row(
            "RE-2026-001", LocalDate.of(2026, 1, 15), "Mandant A GmbH", "Lieferant & Co. KG",
            new BigDecimal("119.00"), "EUR", "1200", "1600", "21");

    private static String text(byte[] csv) {
        return new String(csv, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the file starts with a BOM, the provisional warning and the documented header")
    void bomWarningAndHeader() {
        byte[] csv = DatevCsvWriter.write(List.of(ROW));

        assertThat(csv)
                .as("Excel needs the UTF-8 BOM to open a semicolon CSV correctly")
                .startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        String text = text(csv);
        assertThat(text).startsWith("\uFEFF" + DatevCsvWriter.PROVISIONAL_NOTE);
        assertThat(text).contains(
                "Belegnummer;Belegdatum;Mandant;Lieferant;Betrag;Währung;Sollkonto;Habenkonto;Steuerschlüssel");
        assertThat(text).contains(
                "RE-2026-001;15.01.2026;Mandant A GmbH;Lieferant & Co. KG;119,00;EUR;1200;1600;21");
    }

    @Test
    @DisplayName("the warning is its own line, so the header keeps exactly nine columns")
    void warningDoesNotBecomeAColumn() {
        String header = text(DatevCsvWriter.write(List.of())).lines().toList().get(1);

        assertThat(header.split(";", -1)).hasSize(9);
    }

    @Test
    @DisplayName("a semicolon, a quote or a line break in a value is quoted instead of shifting the row")
    void escapingKeepsColumnsAligned() {
        var tricky = new DatevCsvWriter.Row("RE-2", LocalDate.of(2026, 2, 1), "Müller; Söhne",
                "Sage \"Nord\" GmbH\nZweigstelle", null, null, "1200", "1600", null);
        String csv = text(DatevCsvWriter.write(List.of(tricky)));

        assertThat(csv).contains("\"Müller; Söhne\"");
        assertThat(csv).contains("\"Sage \"\"Nord\"\" GmbH\nZweigstelle\"");
        assertThat(csv).contains(";;1200;1600;");
    }

    @Test
    @DisplayName("an unknown amount is empty, not zero")
    void unknownAmountStaysEmpty() {
        assertThat(DatevCsvWriter.amount(null)).isEmpty();
        assertThat(DatevCsvWriter.amount(new BigDecimal("0"))).isEqualTo("0,00");
        assertThat(DatevCsvWriter.amount(new BigDecimal("1234.5"))).isEqualTo("1234,50");
    }
}
