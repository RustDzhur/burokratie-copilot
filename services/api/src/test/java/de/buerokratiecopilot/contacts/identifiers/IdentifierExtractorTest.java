package de.buerokratiecopilot.contacts.identifiers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.contacts.identifiers.IdentifierExtractor.Found;

/** What is read from the text of a German invoice. */
class IdentifierExtractorTest {

    private static final String INVOICE = """
            Muster GmbH · Musterstraße 1 · 10115 Berlin
            Steuernummer: 12/345/67890
            USt-IdNr.: DE 123 456 789
            Rechnung Nr. 2026-117
            Bitte überweisen Sie auf unser Konto:
            IBAN: DE89 3704 0044 0532 0130 00
            Fragen an buchhaltung@muster-gmbh.de oder Telefon 030 123456.
            """;

    private static List<String> values(List<Found> found, IdentifierKind kind) {
        return found.stream().filter(f -> f.kind() == kind).map(Found::normalized).toList();
    }

    @Test
    @DisplayName("IBAN, USt-IdNr., Steuernummer and e-mail are found in any German spelling")
    void findsTheIdentifiers() {
        List<Found> found = IdentifierExtractor.extract(INVOICE);

        assertThat(values(found, IdentifierKind.IBAN)).containsExactly("DE89370400440532013000");
        assertThat(values(found, IdentifierKind.VAT_ID)).containsExactly("DE123456789");
        assertThat(values(found, IdentifierKind.TAX_NUMBER)).containsExactly("1234567890");
        assertThat(values(found, IdentifierKind.EMAIL)).containsExactly("buchhaltung@muster-gmbh.de");
    }

    @Test
    @DisplayName("an IBAN with wrong check digits is not an IBAN, and a bare number with slashes is not a Steuernummer")
    void doesNotInventIdentifiers() {
        List<Found> found = IdentifierExtractor.extract(
                "Konto DE89 3704 0044 0532 0130 01, Referenz 12/345/67890, Rechnung 2026-117");

        assertThat(values(found, IdentifierKind.IBAN)).isEmpty();
        assertThat(values(found, IdentifierKind.TAX_NUMBER)).isEmpty();
    }

    @Test
    @DisplayName("the same identifier written twice is found once")
    void findsOnce() {
        List<Found> found = IdentifierExtractor.extract("USt-IdNr. DE123456789 ... DE 123 456 789");
        assertThat(values(found, IdentifierKind.VAT_ID)).containsExactly("DE123456789");
    }

    @Test
    @DisplayName("a text without identifiers gives nothing")
    void nothing() {
        assertThat(IdentifierExtractor.extract("Sehr geehrte Damen und Herren, vielen Dank.")).isEmpty();
        assertThat(IdentifierExtractor.extract(null)).isEmpty();
    }
}
