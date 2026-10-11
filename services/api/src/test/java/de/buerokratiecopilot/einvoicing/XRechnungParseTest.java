package de.buerokratiecopilot.einvoicing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.einvoicing.EInvoiceParser.ParsedInvoice;
import de.buerokratiecopilot.einvoicing.EInvoiceParser.Syntax;

/**
 * Verifies reading XRechnung (UBL) files (spec §14, D-005).
 *
 * <p>The samples are written against the standard's own element names, in both syntaxes and with the
 * namespaces of different revisions, because that is what suppliers actually send. What the tests check
 * is that the platform reads what the document says and reports what it could not find — not that it
 * produces a value where there is none.</p>
 */
class XRechnungParseTest {

    /** A UBL invoice as XRechnung writes it. */
    private static final String UBL = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ubl:Invoice xmlns:ubl="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"
                         xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2"
                         xmlns:cac="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2">
              <cbc:CustomizationID>urn:cen.eu:en16931:2017#compliant#urn:xoev-de:kosit:standard:xrechnung_3.0</cbc:CustomizationID>
              <cbc:ID>RE-2026-4711</cbc:ID>
              <cbc:IssueDate>2026-03-01</cbc:IssueDate>
              <cbc:DueDate>2026-03-31</cbc:DueDate>
              <cbc:DocumentCurrencyCode>EUR</cbc:DocumentCurrencyCode>
              <cac:AccountingSupplierParty>
                <cac:Party>
                  <cac:PartyName><cbc:Name>Muster Lieferant GmbH</cbc:Name></cac:PartyName>
                  <cac:PartyTaxScheme><cbc:CompanyID>DE123456789</cbc:CompanyID></cac:PartyTaxScheme>
                </cac:Party>
              </cac:AccountingSupplierParty>
              <cac:AccountingCustomerParty>
                <cac:Party><cac:PartyName><cbc:Name>Beispiel Kunde AG</cbc:Name></cac:PartyName></cac:Party>
              </cac:AccountingCustomerParty>
              <cac:PaymentMeans>
                <cbc:PaymentID>RE-2026-4711</cbc:PaymentID>
                <cac:PayeeFinancialAccount><cbc:ID>DE89370400440532013000</cbc:ID></cac:PayeeFinancialAccount>
              </cac:PaymentMeans>
              <cac:TaxTotal><cbc:TaxAmount currencyID="EUR">19.00</cbc:TaxAmount></cac:TaxTotal>
              <cac:LegalMonetaryTotal>
                <cbc:LineExtensionAmount currencyID="EUR">100.00</cbc:LineExtensionAmount>
                <cbc:TaxExclusiveAmount currencyID="EUR">100.00</cbc:TaxExclusiveAmount>
                <cbc:TaxInclusiveAmount currencyID="EUR">119.00</cbc:TaxInclusiveAmount>
                <cbc:PayableAmount currencyID="EUR">119.00</cbc:PayableAmount>
              </cac:LegalMonetaryTotal>
              <cac:InvoiceLine>
                <cbc:ID>1</cbc:ID>
                <cbc:InvoicedQuantity unitCode="C62">1</cbc:InvoicedQuantity>
                <cbc:LineExtensionAmount currencyID="EUR">100.00</cbc:LineExtensionAmount>
                <cac:Item>
                  <cbc:Name>Wartungspauschale März</cbc:Name>
                  <cac:ClassifiedTaxCategory><cbc:Percent>19.00</cbc:Percent></cac:ClassifiedTaxCategory>
                </cac:Item>
                <cac:Price><cbc:PriceAmount currencyID="EUR">100.00</cbc:PriceAmount></cac:Price>
              </cac:InvoiceLine>
            </ubl:Invoice>
            """;

    private static ParsedInvoice parse(String xml) throws IOException {
        return EInvoiceParser.parse(xml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a UBL invoice is recognised by its root element, not by its file name")
    void recognisesUbl() throws Exception {
        ParsedInvoice invoice = parse(UBL);

        assertThat(invoice.syntax()).isEqualTo(Syntax.UBL);
        assertThat(invoice.syntax().isKnown()).isTrue();
    }

    @Test
    @DisplayName("the header fields are read as the document states them")
    void readsTheHeader() throws Exception {
        ParsedInvoice invoice = parse(UBL);

        assertThat(invoice.invoiceNumber()).isEqualTo("RE-2026-4711");
        assertThat(invoice.issueDate()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(invoice.dueDate()).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(invoice.currency()).isEqualTo("EUR");
        assertThat(invoice.sellerName()).isEqualTo("Muster Lieferant GmbH");
        assertThat(invoice.sellerVatId()).isEqualTo("DE123456789");
        assertThat(invoice.buyerName()).isEqualTo("Beispiel Kunde AG");
        assertThat(invoice.iban()).isEqualTo("DE89370400440532013000");
        assertThat(invoice.paymentReference()).isEqualTo("RE-2026-4711");
    }

    @Test
    @DisplayName("the amounts are read and none of them is recomputed")
    void readsTheAmounts() throws Exception {
        ParsedInvoice invoice = parse(UBL);

        assertThat(invoice.netAmount()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(invoice.taxAmount()).isEqualByComparingTo(new BigDecimal("19.00"));
        assertThat(invoice.grossAmount()).isEqualByComparingTo(new BigDecimal("119.00"));
        assertThat(invoice.payableAmount()).isEqualByComparingTo(new BigDecimal("119.00"));
    }

    @Test
    @DisplayName("the lines are read with their quantity, unit and tax rate")
    void readsTheLines() throws Exception {
        ParsedInvoice invoice = parse(UBL);

        assertThat(invoice.lines()).hasSize(1);
        EInvoiceParser.Line line = invoice.lines().getFirst();
        assertThat(line.lineId()).isEqualTo("1");
        assertThat(line.description()).isEqualTo("Wartungspauschale März");
        assertThat(line.quantity()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(line.unit()).isEqualTo("C62");
        assertThat(line.netAmount()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(line.taxRatePercent()).isEqualByComparingTo(new BigDecimal("19.00"));
    }

    @Test
    @DisplayName("the profile the document declares about itself is reported, not guessed")
    void readsTheDeclaredProfile() throws Exception {
        ParsedInvoice invoice = parse(UBL);

        assertThat(invoice.customizationId()).contains("xrechnung_3.0");
        assertThat(invoice.profileName()).isEqualTo("XRECHNUNG");
    }

    @Test
    @DisplayName("an XML that is not an invoice is refused as such, and says why")
    void refusesUnrelatedXml() {
        String notAnInvoice = """
                <?xml version="1.0"?><Bestellung><Nummer>1</Nummer></Bestellung>
                """;

        assertThatThrownBy(() -> parse(notAnInvoice))
                .isInstanceOf(EInvoiceParser.NotAnEInvoiceException.class)
                .hasMessageContaining("Bestellung")
                .extracting(ex -> ((EInvoiceParser.NotAnEInvoiceException) ex).reason())
                .isEqualTo("UNKNOWN_ROOT_ELEMENT");
    }

    @Test
    @DisplayName("malformed XML is reported as malformed rather than as an unknown invoice")
    void refusesMalformedXml() {
        assertThatThrownBy(() -> parse("<ubl:Invoice xmlns:ubl=\"urn:x\">"))
                .isInstanceOf(EInvoiceParser.NotAnEInvoiceException.class)
                .extracting(ex -> ((EInvoiceParser.NotAnEInvoiceException) ex).reason())
                .isEqualTo("MALFORMED_XML");
    }

    @Test
    @DisplayName("an empty file is not an invoice")
    void refusesEmptyInput() {
        assertThatThrownBy(() -> EInvoiceParser.parse(new byte[0]))
                .isInstanceOf(EInvoiceParser.NotAnEInvoiceException.class)
                .extracting(ex -> ((EInvoiceParser.NotAnEInvoiceException) ex).reason())
                .isEqualTo("EMPTY");
    }

    @Test
    @DisplayName("a document type declaration is refused: an invoice must not read the server's files")
    void refusesDoctype() {
        String attack = """
                <?xml version="1.0"?>
                <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <ubl:Invoice xmlns:ubl="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2">
                  <cbc:ID xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2">&xxe;</cbc:ID>
                </ubl:Invoice>
                """;

        assertThatThrownBy(() -> parse(attack))
                .isInstanceOf(EInvoiceParser.NotAnEInvoiceException.class)
                .extracting(ex -> ((EInvoiceParser.NotAnEInvoiceException) ex).reason())
                .isEqualTo("MALFORMED_XML");
    }

    @Test
    @DisplayName("a field the document does not carry stays missing instead of being invented")
    void missingFieldsStayMissing() throws Exception {
        String minimal = """
                <?xml version="1.0"?>
                <Invoice xmlns="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"
                         xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2">
                  <cbc:ID>RE-1</cbc:ID>
                  <cbc:DocumentCurrencyCode>EUR</cbc:DocumentCurrencyCode>
                </Invoice>
                """;

        ParsedInvoice invoice = parse(minimal);

        assertThat(invoice.invoiceNumber()).isEqualTo("RE-1");
        assertThat(invoice.sellerName()).isNull();
        assertThat(invoice.iban()).isNull();
        assertThat(invoice.issueDate()).isNull();
        assertThat(invoice.grossAmount()).isNull();
        assertThat(invoice.missingRequiredFields())
                .as("what a recorded invoice needs and this one does not have")
                .contains("grossAmount");
    }

    @Test
    @DisplayName("top level elements the platform does not read are named")
    void reportsWhatItDidNotRead() throws Exception {
        String withExtras = UBL.replace("</ubl:Invoice>",
                "  <cbc:BuyerReference>04011000-12345-34</cbc:BuyerReference>\n"
                        + "  <cac:AdditionalDocumentReference><cbc:ID>X</cbc:ID></cac:AdditionalDocumentReference>\n"
                        + "</ubl:Invoice>");

        ParsedInvoice invoice = parse(withExtras);

        assertThat(invoice.unrecognised()).isNotEmpty();
    }

    @Test
    @DisplayName("looksLikeXml separates an XML file from anything else")
    void detectsXml() {
        assertThat(EInvoiceParser.looksLikeXml(UBL.getBytes(StandardCharsets.UTF_8))).isTrue();
        assertThat(EInvoiceParser.looksLikeXml("%PDF-1.7".getBytes(StandardCharsets.UTF_8))).isFalse();
        assertThat(EInvoiceParser.looksLikeXml(new byte[0])).isFalse();
    }
}
