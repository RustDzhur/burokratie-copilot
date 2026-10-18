package de.buerokratiecopilot.einvoicing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Writes an invoice as an XRechnung (spec §14.3).
 *
 * <p><strong>What was missing.</strong> The product reads XRechnung and ZUGFeRD, validates them and stores
 * what they say — and could not produce one, although the integration catalogue promises "Rechnungen als
 * E-Rechnung erzeugen". A customer who has to send e-invoices could receive them and not send them, which is
 * the wrong half.</p>
 *
 * <p><strong>What this writes and what it does not.</strong> It produces UBL XML with the elements the
 * platform itself reads back: the profile identifier, the parties, the dates, the payment details, the
 * totals and the lines. It does not claim to be a complete EN 16931 implementation — the fields the platform
 * does not model are simply not written, and {@link de.buerokratiecopilot.einvoicing.EInvoiceParser} already
 * names what it does not recognise, so a round trip is checkable rather than assumed.</p>
 *
 * <p>The XML is escaped by hand because there is no library here and the alternative — string concatenation
 * without escaping — breaks on the first company called "Müller & Söhne".</p>
 */
public final class XRechnungWriter {

    /** The profile an XRechnung declares about itself. */
    public static final String CUSTOMIZATION_ID =
            "urn:cen.eu:en16931:2017#compliant#urn:xeinkauf.de:kosit:xrechnung_3.0";
    public static final String PROFILE_ID = "urn:fdc:peppol.eu:2017:poacc:billing:01:1.0";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    /** What goes into the document. Everything is required unless the record itself allows null. */
    public record Input(String invoiceNumber, LocalDate issueDate, LocalDate dueDate, String currency,
                        String sellerName, String sellerVatId, String buyerName, String iban,
                        String paymentReference, BigDecimal netAmount, BigDecimal taxAmount,
                        BigDecimal grossAmount, BigDecimal payableAmount, List<Line> lines) {
    }

    /** One line of the invoice. */
    public record Line(String description, BigDecimal quantity, String unit, BigDecimal unitPrice,
                       BigDecimal netAmount, BigDecimal taxRatePercent) {
    }

    private XRechnungWriter() {
    }

    /**
     * Renders the invoice.
     *
     * <p>A missing invoice number or issue date is refused here rather than written as an empty element: an
     * e-invoice without them is not a valid one, and producing it would hand the customer a file that a
     * receiver rejects for a reason they cannot see.</p>
     */
    public static byte[] write(Input input) {
        if (input.invoiceNumber() == null || input.invoiceNumber().isBlank()) {
            throw new IllegalArgumentException("Eine E-Rechnung braucht eine Rechnungsnummer");
        }
        if (input.issueDate() == null) {
            throw new IllegalArgumentException("Eine E-Rechnung braucht ein Rechnungsdatum");
        }
        if (input.lines() == null || input.lines().isEmpty()) {
            throw new IllegalArgumentException("Eine E-Rechnung ohne Positionen ist keine Rechnung");
        }

        StringBuilder xml = new StringBuilder(2048);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<Invoice xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:Invoice-2\"\n");
        xml.append("         xmlns:cac=\"urn:oasis:names:specification:ubl:schema:xsd:")
                .append("CommonAggregateComponents-2\"\n");
        xml.append("         xmlns:cbc=\"urn:oasis:names:specification:ubl:schema:xsd:")
                .append("CommonBasicComponents-2\">\n");
        element(xml, "cbc:CustomizationID", CUSTOMIZATION_ID);
        element(xml, "cbc:ProfileID", PROFILE_ID);
        element(xml, "cbc:ID", input.invoiceNumber());
        element(xml, "cbc:IssueDate", input.issueDate().format(DATE));
        if (input.dueDate() != null) {
            element(xml, "cbc:DueDate", input.dueDate().format(DATE));
        }
        element(xml, "cbc:InvoiceTypeCode", "380");
        element(xml, "cbc:DocumentCurrencyCode", input.currency() == null ? "EUR" : input.currency());

        // Seller.
        xml.append("  <cac:AccountingSupplierParty>\n    <cac:Party>\n");
        if (input.sellerVatId() != null && !input.sellerVatId().isBlank()) {
            xml.append("      <cac:PartyTaxScheme>\n");
            element(xml, "cbc:CompanyID", input.sellerVatId(), "        ");
            xml.append("        <cac:TaxScheme>\n");
            element(xml, "cbc:ID", "VAT", "          ");
            xml.append("        </cac:TaxScheme>\n      </cac:PartyTaxScheme>\n");
        }
        xml.append("      <cac:PartyLegalEntity>\n");
        element(xml, "cbc:RegistrationName", input.sellerName(), "        ");
        xml.append("      </cac:PartyLegalEntity>\n    </cac:Party>\n  </cac:AccountingSupplierParty>\n");

        // Buyer.
        xml.append("  <cac:AccountingCustomerParty>\n    <cac:Party>\n");
        xml.append("      <cac:PartyLegalEntity>\n");
        element(xml, "cbc:RegistrationName", input.buyerName(), "        ");
        xml.append("      </cac:PartyLegalEntity>\n    </cac:Party>\n  </cac:AccountingCustomerParty>\n");

        // Payment.
        if (input.iban() != null && !input.iban().isBlank()) {
            xml.append("  <cac:PaymentMeans>\n");
            element(xml, "cbc:PaymentMeansCode", "58", "    ");
            if (input.paymentReference() != null && !input.paymentReference().isBlank()) {
                element(xml, "cbc:PaymentID", input.paymentReference(), "    ");
            }
            xml.append("    <cac:PayeeFinancialAccount>\n");
            element(xml, "cbc:ID", input.iban(), "      ");
            xml.append("    </cac:PayeeFinancialAccount>\n  </cac:PaymentMeans>\n");
        }

        // Tax total. The platform's own parser reads the tax amount from here, so a document without it is
        // read back as one with no tax - the round trip caught exactly that.
        xml.append("  <cac:TaxTotal>\n");
        amount(xml, "cbc:TaxAmount", input.taxAmount(), "    ", "currencyID",
                input.currency() == null ? "EUR" : input.currency());
        xml.append("  </cac:TaxTotal>\n");

        // Totals. Written as they are given: the writer computes nothing, so the PDF, the record and the
        // e-invoice can never disagree because one of them recalculated.
        xml.append("  <cac:LegalMonetaryTotal>\n");
        amount(xml, "cbc:LineExtensionAmount", input.netAmount());
        amount(xml, "cbc:TaxExclusiveAmount", input.netAmount());
        amount(xml, "cbc:TaxInclusiveAmount", input.grossAmount());
        amount(xml, "cbc:PayableAmount", input.payableAmount() == null
                ? input.grossAmount() : input.payableAmount());
        xml.append("  </cac:LegalMonetaryTotal>\n");

        int position = 1;
        for (Line line : input.lines()) {
            xml.append("  <cac:InvoiceLine>\n");
            element(xml, "cbc:ID", String.valueOf(position++), "    ");
            amount(xml, "cbc:InvoicedQuantity", line.quantity(), "    ", "unitCode",
                    line.unit() == null || line.unit().isBlank() ? "C62" : line.unit());
            amount(xml, "cbc:LineExtensionAmount", line.netAmount(), "    ", null, null);
            xml.append("    <cac:Item>\n");
            element(xml, "cbc:Name", line.description(), "      ");
            xml.append("      <cac:ClassifiedTaxCategory>\n");
            element(xml, "cbc:ID", "S", "        ");
            amount(xml, "cbc:Percent", line.taxRatePercent(), "        ", null, null);
            xml.append("        <cac:TaxScheme>\n");
            element(xml, "cbc:ID", "VAT", "          ");
            xml.append("        </cac:TaxScheme>\n      </cac:ClassifiedTaxCategory>\n");
            xml.append("    </cac:Item>\n");
            xml.append("    <cac:Price>\n");
            amount(xml, "cbc:PriceAmount", line.unitPrice(), "      ", null, null);
            xml.append("    </cac:Price>\n");
            xml.append("  </cac:InvoiceLine>\n");
        }

        xml.append("</Invoice>\n");
        return xml.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void element(StringBuilder xml, String name, String value) {
        element(xml, name, value, "  ");
    }

    private static void element(StringBuilder xml, String name, String value, String indent) {
        if (value == null) {
            return;
        }
        xml.append(indent).append('<').append(name).append('>')
                .append(escape(value))
                .append("</").append(name).append(">\n");
    }

    private static void amount(StringBuilder xml, String name, BigDecimal value) {
        amount(xml, name, value, "  ", null, null);
    }

    private static void amount(StringBuilder xml, String name, BigDecimal value, String indent,
                               String attribute, String attributeValue) {
        if (value == null) {
            return;
        }
        xml.append(indent).append('<').append(name);
        if (attribute != null && attributeValue != null) {
            xml.append(' ').append(attribute).append("=\"").append(escape(attributeValue)).append('"');
        }
        xml.append('>').append(value.toPlainString())
                .append("</").append(name).append(">\n");
    }

    /** XML escaping. Without it the first company called "Müller & Söhne" produces a broken document. */
    static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&apos;");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }
}
