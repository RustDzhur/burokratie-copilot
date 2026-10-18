package de.buerokratiecopilot.einvoicing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An invoice can be written as an XRechnung and read back (spec §14.3).
 *
 * <p><strong>Why the round trip is the test.</strong> The platform could read XRechnung and ZUGFeRD and
 * could not write one, although the catalogue promises to produce them. Writing XML that looks right is easy
 * and worthless; what matters is that the product's own parser — the one that reads customer documents — gets
 * the same values back. If the parser and the writer disagree, one of them is wrong and the round trip says
 * so without a validator the project does not have.</p>
 */
class XRechnungWriterTest {

    private static XRechnungWriter.Input invoice() {
        return new XRechnungWriter.Input("RE-2026-0042", LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 3, 31), "EUR",
                // A company name with an ampersand and an umlaut: the first thing unescaped concatenation
                // breaks on.
                "Müller & Söhne GmbH", "DE123456789", "Beispiel Kunde AG",
                "DE89370400440532013000", "RE-2026-0042",
                new BigDecimal("100.00"), new BigDecimal("19.00"), new BigDecimal("119.00"),
                new BigDecimal("119.00"),
                List.of(new XRechnungWriter.Line("Beratung", BigDecimal.ONE, "C62",
                        new BigDecimal("100.00"), new BigDecimal("100.00"), new BigDecimal("19.00"))));
    }

    @Test
    @DisplayName("what the writer produces, the platform's own parser reads back")
    void theWrittenInvoiceParsesBack() throws Exception {
        byte[] xml = XRechnungWriter.write(invoice());

        var parsed = EInvoiceParser.parse(xml);

        assertThat(parsed.syntax()).isEqualTo(EInvoiceParser.Syntax.UBL);
        assertThat(parsed.invoiceNumber()).isEqualTo("RE-2026-0042");
        assertThat(parsed.issueDate()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(parsed.dueDate()).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(parsed.currency()).isEqualTo("EUR");
        assertThat(parsed.sellerName()).isEqualTo("Müller & Söhne GmbH");
        assertThat(parsed.sellerVatId()).isEqualTo("DE123456789");
        assertThat(parsed.buyerName()).isEqualTo("Beispiel Kunde AG");
        assertThat(parsed.iban()).isEqualTo("DE89370400440532013000");
        assertThat(parsed.netAmount()).isEqualByComparingTo("100.00");
        assertThat(parsed.taxAmount()).isEqualByComparingTo("19.00");
        assertThat(parsed.grossAmount()).isEqualByComparingTo("119.00");
        assertThat(parsed.lines()).hasSize(1);
        assertThat(parsed.lines().get(0).description()).isEqualTo("Beratung");
        assertThat(parsed.lines().get(0).netAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("the document declares the profile it claims to follow")
    void theDocumentNamesItsProfile() throws Exception {
        var parsed = EInvoiceParser.parse(XRechnungWriter.write(invoice()));

        assertThat(parsed.customizationId()).isEqualTo(XRechnungWriter.CUSTOMIZATION_ID);
        assertThat(parsed.missingRequiredFields())
                .as("a generated invoice that its own reader calls incomplete would be worse than none")
                .isEmpty();
    }

    @Test
    @DisplayName("an ampersand in a company name does not break the document")
    void escapingWorks() throws Exception {
        byte[] xml = XRechnungWriter.write(invoice());
        String text = new String(xml, java.nio.charset.StandardCharsets.UTF_8);

        // The raw name must not appear: it would mean an unescaped ampersand and a broken XML document.
        assertThat(text).doesNotContain("Müller & Söhne GmbH</cbc:RegistrationName>");
        assertThat(text).contains("Müller &amp; Söhne GmbH");
        // And the parser still reads the original text back.
        assertThat(EInvoiceParser.parse(xml).sellerName()).isEqualTo("Müller & Söhne GmbH");
    }

    @Test
    @DisplayName("an invoice without a number, a date or lines is refused instead of written")
    void anIncompleteInvoiceIsRefused() {
        // A file that a receiver rejects for a reason the sender cannot see is worse than an error here.
        assertThatThrownBy(() -> XRechnungWriter.write(new XRechnungWriter.Input(
                null, LocalDate.of(2026, 3, 1), null, "EUR", "A", null, "B", null, null,
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE,
                List.of(new XRechnungWriter.Line("x", BigDecimal.ONE, "C62",
                        BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Rechnungsnummer");

        assertThatThrownBy(() -> XRechnungWriter.write(new XRechnungWriter.Input(
                "RE-1", null, null, "EUR", "A", null, "B", null, null,
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE,
                List.of(new XRechnungWriter.Line("x", BigDecimal.ONE, "C62",
                        BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Rechnungsdatum");

        assertThatThrownBy(() -> XRechnungWriter.write(new XRechnungWriter.Input(
                "RE-1", LocalDate.of(2026, 3, 1), null, "EUR", "A", null, "B", null, null,
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE, List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Positionen");
    }
}
