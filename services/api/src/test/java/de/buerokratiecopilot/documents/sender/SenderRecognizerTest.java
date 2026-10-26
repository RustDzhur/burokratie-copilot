package de.buerokratiecopilot.documents.sender;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.documents.sender.SenderRecognizer.Kind;

class SenderRecognizerTest {

    private static final String LETTERHEAD = """
            Stadtwerke Musterstadt GmbH
            Hauptstraße 12, 12345 Musterstadt
            Telefon: 0123 / 456 789-0
            Telefax: 0123 / 456 789-99
            E-Mail: service@stadtwerke-musterstadt.example
            www.stadtwerke-musterstadt.example
            USt-IdNr.: DE123456789

            Erika Mustermann
            Musterweg 5
            54321 Beispielstadt

            Rechnung
            Rechnungsnummer: RE-2026-0412
            Zahlbar auf das Konto IBAN DE89 3704 0044 0532 0130 00.
            """;

    @Test
    @DisplayName("a letterhead gives the name, the address, the telephone (not the fax), the e-mail, the website, the VAT id and the IBAN")
    void letterhead() {
        var sender = SenderRecognizer.recognise(LETTERHEAD).orElseThrow();
        assertThat(sender.name()).isEqualTo("Stadtwerke Musterstadt GmbH");
        assertThat(sender.kind()).isEqualTo(Kind.ORGANIZATION);
        assertThat(sender.street()).isEqualTo("Hauptstraße 12");
        assertThat(sender.postalCode()).isEqualTo("12345");
        assertThat(sender.city()).isEqualTo("Musterstadt");
        assertThat(sender.phone()).isEqualTo("0123 / 456 789-0");
        assertThat(sender.email()).isEqualTo("service@stadtwerke-musterstadt.example");
        assertThat(sender.website()).isEqualTo("www.stadtwerke-musterstadt.example");
        assertThat(sender.vatId()).isEqualTo("DE123456789");
        assertThat(sender.iban()).isEqualTo("DE89370400440532013000");
        assertThat(sender.addressLine()).isEqualTo("Hauptstraße 12, 12345 Musterstadt");
    }

    @Test
    @DisplayName("a contact line with an organisation word in the e-mail address is not taken for the sender")
    void contactLineIsNotTheSender() {
        var sender = SenderRecognizer.recognise("""
                Immobilienverwaltung Krüger
                Schlossstraße 5 - 01067 Dresden - Tel. 0351 445599 - verwaltung@immo-krueger.example
                Mietvertrag
                Miete: 989,67 EUR monatlich
                """).orElseThrow();
        assertThat(sender.name()).isEqualTo("Immobilienverwaltung Krüger");
        assertThat(sender.street()).isEqualTo("Schlossstraße 5");
        assertThat(sender.postalCode()).isEqualTo("01067");
        assertThat(sender.city()).startsWith("Dresden");
    }

    @Test
    @DisplayName("the recipient's address further down is not taken for the sender's")
    void recipientIsNotTheSender() {
        var sender = SenderRecognizer.recognise(LETTERHEAD).orElseThrow();
        assertThat(sender.city()).isNotEqualTo("Beispielstadt");
        assertThat(sender.street()).doesNotContain("Musterweg");
    }

    @Test
    @DisplayName("the small return-address line above the address window is read as name, street and place")
    void returnAddressLine() {
        var sender = SenderRecognizer.recognise("""
                Beispiel Software AG · Industriestraße 4 · 80331 München
                Erika Mustermann
                Musterweg 5
                54321 Beispielstadt
                Rechnung 88-2026-33
                """).orElseThrow();
        assertThat(sender.name()).isEqualTo("Beispiel Software AG");
        assertThat(sender.street()).isEqualTo("Industriestraße 4");
        assertThat(sender.postalCode()).isEqualTo("80331");
        assertThat(sender.city()).isEqualTo("München");
    }

    @Test
    @DisplayName("an authority is recognised as one, with its address on two lines")
    void authority() {
        var sender = SenderRecognizer.recognise("""
                Finanzamt Musterstadt
                Amtsstraße 7
                12345 Musterstadt
                Steuernummer 21/815/08150
                Einkommensteuerbescheid 2025
                """).orElseThrow();
        assertThat(sender.name()).isEqualTo("Finanzamt Musterstadt");
        assertThat(sender.kind()).isEqualTo(Kind.AUTHORITY);
        assertThat(sender.street()).isEqualTo("Amtsstraße 7");
        assertThat(sender.postalCode()).isEqualTo("12345");
    }

    @Test
    @DisplayName("a private person writing is a person, found by the address under their name")
    void person() {
        var sender = SenderRecognizer.recognise("""
                Erika Mustermann
                Musterweg 5
                54321 Beispielstadt

                An das Finanzamt Musterstadt
                Widerspruch gegen den Bescheid
                """).orElseThrow();
        assertThat(sender.name()).isEqualTo("Erika Mustermann");
        assertThat(sender.kind()).isEqualTo(Kind.PERSON);
        assertThat(sender.city()).isEqualTo("Beispielstadt");
    }

    @Test
    @DisplayName("when the text does not say who sent it, nothing is guessed")
    void nothingIsGuessed() {
        assertThat(SenderRecognizer.recognise("Arztbrief\nSehr geehrte Frau Kollegin, wir berichten über die Behandlung.\nDiagnose: Bronchitis")).isEmpty();
        assertThat(SenderRecognizer.recognise("Rechnung\nRechnungsnummer 4711\nBetrag 119,00 EUR")).isEmpty();
        assertThat(SenderRecognizer.recognise("   ")).isEmpty();
        assertThat(SenderRecognizer.recognise(null)).isEmpty();
    }

    @Test
    @DisplayName("a letter whose first line is the title still finds the sender in the lines below")
    void titleFirst() {
        var sender = SenderRecognizer.recognise("""
                Mieterhöhungsverlangen gemäß § 558 BGB
                Hausverwaltung Beispiel GmbH
                Parkallee 3
                10115 Berlin
                Sehr geehrte Frau Mustermann,
                """).orElseThrow();
        assertThat(sender.name()).isEqualTo("Hausverwaltung Beispiel GmbH");
        assertThat(sender.city()).isEqualTo("Berlin");
    }
}
