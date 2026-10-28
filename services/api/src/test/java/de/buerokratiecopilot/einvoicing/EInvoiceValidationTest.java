package de.buerokratiecopilot.einvoicing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.einvoicing.EInvoiceParser.ParsedInvoice;
import de.buerokratiecopilot.einvoicing.EInvoiceParser.Syntax;

/**
 * Verifies the CII syntax and what the platform says about a file it read (spec §14, D-005).
 *
 * <p>ZUGFeRD carries CII, XRechnung carries UBL, and both are the same European standard. The CII
 * branch had no test of its own until this class; a parser that reads one syntax and silently produces
 * nothing for the other is the kind of gap that only shows up with a real supplier's file.</p>
 */
class EInvoiceValidationTest {

    /** A CII invoice as ZUGFeRD writes it, with the namespaces of the 100 revision. */
    private static final String CII = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rsm:CrossIndustryInvoice
                xmlns:rsm="urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100"
                xmlns:ram="urn:un:unece:uncefact:data:standard:ReusableAggregateBusinessInformationEntity:100"
                xmlns:udt="urn:un:unece:uncefact:data:standard:UnqualifiedDataType:100">
              <rsm:ExchangedDocumentContext>
                <ram:GuidelineSpecifiedDocumentContextParameter>
                  <ram:ID>urn:cen.eu:en16931:2017#conformant#urn:factur-x.eu:1p0:extended</ram:ID>
                </ram:GuidelineSpecifiedDocumentContextParameter>
              </rsm:ExchangedDocumentContext>
              <rsm:ExchangedDocument>
                <ram:ID>RE-2026-815</ram:ID>
                <ram:IssueDateTime>
                  <udt:DateTimeString format="102">20260301</udt:DateTimeString>
                </ram:IssueDateTime>
              </rsm:ExchangedDocument>
              <rsm:SupplyChainTradeTransaction>
                <ram:IncludedSupplyChainTradeLineItem>
                  <ram:AssociatedDocumentLineDocument><ram:LineID>1</ram:LineID></ram:AssociatedDocumentLineDocument>
                  <ram:SpecifiedTradeProduct><ram:Name>Wartung Februar</ram:Name></ram:SpecifiedTradeProduct>
                  <ram:SpecifiedLineTradeAgreement>
                    <ram:NetPriceProductTradePrice>
                      <ram:ChargeAmount>200.00</ram:ChargeAmount>
                    </ram:NetPriceProductTradePrice>
                  </ram:SpecifiedLineTradeAgreement>
                  <ram:SpecifiedLineTradeDelivery>
                    <ram:BilledQuantity unitCode="C62">1</ram:BilledQuantity>
                  </ram:SpecifiedLineTradeDelivery>
                  <ram:SpecifiedLineTradeSettlement>
                    <ram:ApplicableTradeTax><ram:RateApplicablePercent>19.00</ram:RateApplicablePercent></ram:ApplicableTradeTax>
                    <ram:SpecifiedTradeSettlementLineMonetarySummation>
                      <ram:LineTotalAmount>200.00</ram:LineTotalAmount>
                    </ram:SpecifiedTradeSettlementLineMonetarySummation>
                  </ram:SpecifiedLineTradeSettlement>
                </ram:IncludedSupplyChainTradeLineItem>
                <ram:ApplicableHeaderTradeAgreement>
                  <ram:SellerTradeParty>
                    <ram:Name>Muster Lieferant GmbH</ram:Name>
                    <ram:SpecifiedTaxRegistration><ram:ID>DE123456789</ram:ID></ram:SpecifiedTaxRegistration>
                  </ram:SellerTradeParty>
                  <ram:BuyerTradeParty><ram:Name>Beispiel Kunde AG</ram:Name></ram:BuyerTradeParty>
                </ram:ApplicableHeaderTradeAgreement>
                <ram:ApplicableHeaderTradeSettlement>
                  <ram:InvoiceCurrencyCode>EUR</ram:InvoiceCurrencyCode>
                  <ram:SpecifiedTradeSettlementHeaderMonetarySummation>
                    <ram:TaxBasisTotalAmount>200.00</ram:TaxBasisTotalAmount>
                    <ram:TaxTotalAmount>38.00</ram:TaxTotalAmount>
                    <ram:GrandTotalAmount>238.00</ram:GrandTotalAmount>
                    <ram:DuePayableAmount>238.00</ram:DuePayableAmount>
                  </ram:SpecifiedTradeSettlementHeaderMonetarySummation>
                  <ram:SpecifiedTradePaymentTerms>
                    <ram:DueDateDateTime>
                      <udt:DateTimeString format="102">20260331</udt:DateTimeString>
                    </ram:DueDateDateTime>
                  </ram:SpecifiedTradePaymentTerms>
                </ram:ApplicableHeaderTradeSettlement>
              </rsm:SupplyChainTradeTransaction>
            </rsm:CrossIndustryInvoice>
            """;

    private static ParsedInvoice parse(String xml) throws IOException {
        return EInvoiceParser.parse(xml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a CII invoice is recognised and read, with the amounts the document states")
    void readsCii() throws Exception {
        ParsedInvoice invoice = parse(CII);

        assertThat(invoice.syntax()).isEqualTo(Syntax.CII);
        assertThat(invoice.invoiceNumber()).isEqualTo("RE-2026-815");
        assertThat(invoice.currency()).isEqualTo("EUR");
        assertThat(invoice.sellerName()).isEqualTo("Muster Lieferant GmbH");
        assertThat(invoice.sellerVatId()).isEqualTo("DE123456789");
        assertThat(invoice.buyerName()).isEqualTo("Beispiel Kunde AG");
        assertThat(invoice.netAmount()).isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(invoice.taxAmount()).isEqualByComparingTo(new BigDecimal("38.00"));
        assertThat(invoice.grossAmount()).isEqualByComparingTo(new BigDecimal("238.00"));
        assertThat(invoice.payableAmount()).isEqualByComparingTo(new BigDecimal("238.00"));
    }

    @Test
    @DisplayName("the CII dates are read from their formatted elements, including the due date in the terms")
    void readsCiiDates() throws Exception {
        ParsedInvoice invoice = parse(CII);

        assertThat(invoice.issueDate()).isEqualTo(LocalDate.of(2026, 3, 1));
        // The due date sits inside the payment terms, where it is easy to miss.
        assertThat(invoice.dueDate()).isEqualTo(LocalDate.of(2026, 3, 31));
    }

    @Test
    @DisplayName("the CII lines are read with quantity, unit and tax rate")
    void readsCiiLines() throws Exception {
        ParsedInvoice invoice = parse(CII);

        assertThat(invoice.lines()).hasSize(1);
        EInvoiceParser.Line line = invoice.lines().getFirst();
        assertThat(line.lineId()).isEqualTo("1");
        assertThat(line.description()).isEqualTo("Wartung Februar");
        assertThat(line.quantity()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(line.unit()).isEqualTo("C62");
        assertThat(line.netAmount()).isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(line.taxRatePercent()).isEqualByComparingTo(new BigDecimal("19.00"));
    }

    @Test
    @DisplayName("the profile the document declares is reported as ZUGFeRD, not guessed")
    void readsCiiProfile() throws Exception {
        ParsedInvoice invoice = parse(CII);

        assertThat(invoice.customizationId()).contains("factur-x");
        assertThat(invoice.profileName()).isEqualTo("ZUGFERD");
        assertThat(invoice.missingRequiredFields()).isEmpty();
    }

    @Test
    @DisplayName("a complete invoice reports nothing missing")
    void completeInvoiceHasNothingMissing() throws Exception {
        assertThat(parse(CII).missingRequiredFields()).isEmpty();
    }

    @Test
    @DisplayName("what a file is missing is named, so a user can act on it")
    void namesWhatIsMissing() throws Exception {
        String withNothing = """
                <?xml version="1.0"?>
                <CrossIndustryInvoice
                    xmlns="urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100">
                </CrossIndustryInvoice>
                """;

        ParsedInvoice invoice = parse(withNothing);

        assertThat(invoice.syntax()).isEqualTo(Syntax.CII);
        assertThat(invoice.missingRequiredFields())
                .contains("invoiceNumber", "currency", "grossAmount");
    }

    @Test
    @DisplayName("the diagnostics carry the syntax, the profile and what was not read")
    void diagnosticsDescribeTheFile() throws Exception {
        var diagnostics = parse(CII).asDiagnostics();

        assertThat(diagnostics).containsEntry("syntax", "CII");
        assertThat(diagnostics).containsEntry("invoiceNumber", "RE-2026-815");
        assertThat(diagnostics.get("lines")).isEqualTo(1);
    }

    @Test
    @DisplayName("an amount written with a comma is read, because suppliers write it that way")
    void readsAmountsWithComma() throws Exception {
        String withComma = CII.replace("<ram:GrandTotalAmount>238.00</ram:GrandTotalAmount>",
                "<ram:GrandTotalAmount>238,00</ram:GrandTotalAmount>");

        assertThat(parse(withComma).grossAmount()).isEqualByComparingTo(new BigDecimal("238.00"));
    }

    @Test
    @DisplayName("a credit note is recognised as an electronic invoice, not refused")
    void readsCreditNote() throws Exception {
        String creditNote = """
                <?xml version="1.0"?>
                <CreditNote xmlns="urn:oasis:names:specification:ubl:schema:xsd:CreditNote-2"
                            xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2">
                  <cbc:ID>GS-2026-1</cbc:ID>
                  <cbc:DocumentCurrencyCode>EUR</cbc:DocumentCurrencyCode>
                </CreditNote>
                """;

        ParsedInvoice invoice = parse(creditNote);

        assertThat(invoice.syntax()).isEqualTo(Syntax.UBL);
        assertThat(invoice.invoiceNumber()).isEqualTo("GS-2026-1");
    }

    @Test
    @DisplayName("an unknown syntax is a statement about the file, not a crash")
    void unknownSyntaxIsReported() {
        assertThat(EInvoiceParser.detectSyntax("<Something/>".getBytes(StandardCharsets.UTF_8)))
                .isEmpty();
        assertThat(EInvoiceParser.detectSyntax(CII.getBytes(StandardCharsets.UTF_8)))
                .contains(Syntax.CII);
    }
}
