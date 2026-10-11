package de.buerokratiecopilot.einvoicing;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Reads a structured electronic invoice (spec §14, D-005).
 *
 * <p>Two syntaxes exist for the same European standard, and both are supported: UBL, which XRechnung
 * uses, and CII, which ZUGFeRD and Factur-X use. Which one a file is, is decided by its root element,
 * not by its file name.</p>
 *
 * <p>Elements are looked up by <strong>local name</strong> rather than by a fixed namespace URI. The
 * standard has been revised repeatedly and the namespaces moved with it; matching on the URI would mean
 * refusing files that are perfectly valid because they were written against a different minor version.
 * The root element is still checked against the two known syntaxes, so an unrelated XML file is reported
 * as unsupported instead of being half-read.</p>
 *
 * <p>What this class does not do is interpret. It reads what the document says — amounts, dates, names,
 * identifiers — and reports anything it could not find. It applies no tax rule and invents no value: a
 * missing field stays missing, and the caller decides what that means.</p>
 */
public final class EInvoiceParser {

    /** The two syntaxes of EN 16931 the platform reads. */
    public enum Syntax {
        UBL, CII, UNKNOWN;

        public boolean isKnown() {
            return this != UNKNOWN;
        }
    }

    /** One invoice line as the document states it. */
    public record Line(String lineId, String description, BigDecimal quantity, String unit,
                       BigDecimal unitPrice, BigDecimal netAmount, BigDecimal taxRatePercent) {
    }

    /**
     * What the document says.
     *
     * @param customizationId the profile identifier the document declares about itself
     * @param unrecognised    top level elements the platform does not read, named so the gap is visible
     */
    public record ParsedInvoice(Syntax syntax, String customizationId, String profileName,
                                String invoiceNumber, LocalDate issueDate, LocalDate dueDate,
                                String currency, String sellerName, String sellerVatId,
                                String buyerName, String iban, String paymentReference,
                                BigDecimal netAmount, BigDecimal taxAmount, BigDecimal grossAmount,
                                BigDecimal payableAmount, List<Line> lines,
                                List<String> unrecognised, String specificationVersion) {

        public Map<String, Object> asDiagnostics() {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("syntax", syntax.name());
            details.put("customizationId", customizationId);
            details.put("profile", profileName);
            details.put("invoiceNumber", invoiceNumber);
            details.put("currency", currency);
            details.put("lines", lines.size());
            details.put("unrecognised", unrecognised);
            return details;
        }

        /** The fields a recorded invoice needs; anything absent is named rather than assumed. */
        public List<String> missingRequiredFields() {
            List<String> missing = new ArrayList<>();
            if (invoiceNumber == null || invoiceNumber.isBlank()) {
                missing.add("invoiceNumber");
            }
            if (currency == null || currency.isBlank()) {
                missing.add("currency");
            }
            if (grossAmount == null && payableAmount == null) {
                missing.add("grossAmount");
            }
            return missing;
        }
    }

    /** Why a file is not an electronic invoice. */
    public static final class NotAnEInvoiceException extends IOException {
        private final String reason;

        NotAnEInvoiceException(String message, String reason) {
            super(message);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    private EInvoiceParser() {
    }

    /**
     * Parses the XML of an electronic invoice.
     *
     * @throws NotAnEInvoiceException when the document is not an invoice in a known syntax. That is a
     *                                statement about the file, not a failure of the platform, and the
     *                                caller records it as unsupported.
     */
    public static ParsedInvoice parse(byte[] xml) throws IOException {
        if (xml == null || xml.length == 0) {
            throw new NotAnEInvoiceException("Die Datei ist leer", "EMPTY");
        }
        Document document = parseXml(xml);
        Element root = document.getDocumentElement();
        String rootName = localName(root);

        return switch (rootName) {
            case "Invoice", "CreditNote" -> parseUbl(root);
            case "CrossIndustryInvoice" -> parseCii(root);
            default -> throw new NotAnEInvoiceException(
                    "Das XML ist keine elektronische Rechnung: Wurzelelement ist <" + rootName + ">",
                    "UNKNOWN_ROOT_ELEMENT");
        };
    }

    /** Whether the bytes look like XML at all, which is the first question a caller has. */
    public static boolean looksLikeXml(byte[] content) {
        if (content == null || content.length == 0) {
            return false;
        }
        String head = new String(content, 0, Math.min(content.length, 200), StandardCharsets.UTF_8).strip();
        return head.startsWith("<?xml") || head.startsWith("<");
    }

    // ------------------------------------------------------------------ UBL (XRechnung)

    private static ParsedInvoice parseUbl(Element root) {
        List<Line> lines = new ArrayList<>();
        for (Element line : children(root, "InvoiceLine", "CreditNoteLine")) {
            lines.add(new Line(
                    text(line, "ID"),
                    text(descendant(line, "Item"), "Name"),
                    decimal(eitherText(line, "InvoicedQuantity", "CreditedQuantity")),
                    attribute(line, "InvoicedQuantity", "CreditedQuantity", "unitCode"),
                    decimal(text(descendant(line, "Price"), "PriceAmount")),
                    decimal(text(line, "LineExtensionAmount")),
                    decimal(text(descendant(line, "Item", "ClassifiedTaxCategory"), "Percent"))));
        }

        Element supplier = descendant(root, "AccountingSupplierParty", "Party");
        Element customer = descendant(root, "AccountingCustomerParty", "Party");
        Element total = descendant(root, "LegalMonetaryTotal");
        Element paymentMeans = child(root, "PaymentMeans");

        BigDecimal taxAmount = null;
        Element taxTotal = child(root, "TaxTotal");
        if (taxTotal != null) {
            taxAmount = decimal(text(taxTotal, "TaxAmount"));
        }

        return new ParsedInvoice(Syntax.UBL,
                text(root, "CustomizationID"),
                profileOf(text(root, "CustomizationID")),
                text(root, "ID"),
                date(text(root, "IssueDate")),
                date(text(root, "DueDate")),
                text(root, "DocumentCurrencyCode"),
                partyName(supplier),
                partyVatId(supplier),
                partyName(customer),
                text(descendant(paymentMeans, "PayeeFinancialAccount"), "ID"),
                text(root, "PaymentMeans", "PaymentID"),
                decimal(text(total, "TaxExclusiveAmount")),
                taxAmount,
                decimal(text(total, "TaxInclusiveAmount")),
                decimal(text(total, "PayableAmount")),
                List.copyOf(lines),
                unrecognised(root, List.of("UBLVersionID", "CustomizationID", "ProfileID", "ID",
                        "IssueDate", "DueDate", "InvoiceTypeCode", "Note", "DocumentCurrencyCode",
                        "TaxCurrencyCode", "BuyerReference", "AccountingSupplierParty",
                        "AccountingCustomerParty", "PayeeParty", "Delivery", "PaymentMeans",
                        "PaymentTerms", "TaxTotal", "LegalMonetaryTotal", "InvoiceLine",
                        "CreditNoteLine")),
                null);
    }

    // ------------------------------------------------------------------ CII (ZUGFeRD)

    private static ParsedInvoice parseCii(Element root) {
        Element transaction = child(root, "SupplyChainTradeTransaction");
        Element agreement = transaction == null ? null : child(transaction, "ApplicableHeaderTradeAgreement");
        Element settlement = transaction == null ? null
                : child(transaction, "ApplicableHeaderTradeSettlement");
        Element document = child(root, "ExchangedDocument");
        Element context = child(root, "ExchangedDocumentContext");

        List<Line> lines = new ArrayList<>();
        if (transaction != null) {
            for (Element line : children(transaction, "IncludedSupplyChainTradeLineItem")) {
                Element product = descendant(line, "SpecifiedTradeProduct");
                Element agreementLine = descendant(line, "SpecifiedLineTradeAgreement");
                Element delivery = descendant(line, "SpecifiedLineTradeDelivery");
                Element lineSettlement = descendant(line, "SpecifiedLineTradeSettlement");
                Element summation = lineSettlement == null ? null
                        : descendant(lineSettlement, "SpecifiedTradeSettlementLineMonetarySummation");
                lines.add(new Line(
                        text(descendant(line, "AssociatedDocumentLineDocument"), "LineID"),
                        text(product, "Name"),
                        decimal(text(delivery, "BilledQuantity")),
                        attribute(delivery, "BilledQuantity", "BilledQuantity", "unitCode"),
                        decimal(text(descendant(agreementLine, "NetPriceProductTradePrice"),
                                "ChargeAmount")),
                        decimal(text(summation, "LineTotalAmount")),
                        decimal(text(descendant(lineSettlement, "ApplicableTradeTax"), "RateApplicablePercent"))));
            }
        }

        Element summation = settlement == null ? null
                : descendant(settlement, "SpecifiedTradeSettlementHeaderMonetarySummation");
        Element seller = agreement == null ? null : descendant(agreement, "SellerTradeParty");
        Element buyer = agreement == null ? null : descendant(agreement, "BuyerTradeParty");
        Element account = settlement == null ? null
                : descendant(settlement, "PayeePartyCreditorFinancialAccount");

        String customizationId = context == null ? null
                : text(descendant(context, "GuidelineSpecifiedDocumentContextParameter"), "ID");

        return new ParsedInvoice(Syntax.CII, customizationId, profileOf(customizationId),
                document == null ? null : text(document, "ID"),
                dateTime(text(document, "IssueDateTime")),
                dueDateOf(settlement),
                settlement == null ? null : text(settlement, "InvoiceCurrencyCode"),
                seller == null ? null : text(seller, "Name"),
                seller == null ? null : text(descendant(seller, "SpecifiedTaxRegistration"), "ID"),
                buyer == null ? null : text(buyer, "Name"),
                account == null ? null : text(account, "IBANID"),
                settlement == null ? null : text(descendant(settlement, "PaymentReference"), "ID"),
                summation == null ? null : decimal(text(summation, "TaxBasisTotalAmount")),
                summation == null ? null : decimal(text(summation, "TaxTotalAmount")),
                summation == null ? null : decimal(text(summation, "GrandTotalAmount")),
                summation == null ? null : decimal(text(summation, "DuePayableAmount")),
                List.copyOf(lines),
                unrecognised(root, List.of("ExchangedDocumentContext", "ExchangedDocument",
                        "SupplyChainTradeTransaction")),
                null);
    }

    /** The due date lives inside the payment terms in CII, which is easy to miss. */
    private static LocalDate dueDateOf(Element settlement) {
        if (settlement == null) {
            return null;
        }
        for (Element terms : children(settlement, "SpecifiedTradePaymentTerms")) {
            LocalDate due = dateTime(text(terms, "DueDateDateTime"));
            if (due != null) {
                return due;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    private static Document parseXml(byte[] xml) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // An invoice is untrusted input: no DTDs, no external entities, no schemas fetched over the
            // network. Reading a supplier's file must not become a way to read the server's files.
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(new ByteArrayInputStream(xml));
            document.getDocumentElement().normalize();
            return document;
        }
        catch (ParserConfigurationException | SAXException ex) {
            throw new NotAnEInvoiceException(
                    "Das XML konnte nicht gelesen werden: " + ex.getMessage(), "MALFORMED_XML");
        }
    }

    private static String localName(Node node) {
        if (node == null) {
            return null;
        }
        String local = node.getLocalName();
        return local != null ? local : node.getNodeName();
    }

    /** Direct children with one of the given local names. */
    private static List<Element> children(Element parent, String... names) {
        List<Element> found = new ArrayList<>();
        if (parent == null) {
            return found;
        }
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            String name = localName(node);
            for (String candidate : names) {
                if (candidate.equals(name)) {
                    found.add((Element) node);
                    break;
                }
            }
        }
        return found;
    }

    /** The first direct child with the given local name. */
    private static Element child(Element parent, String name) {
        List<Element> found = children(parent, name);
        return found.isEmpty() ? null : found.getFirst();
    }

    /**
     * Walks a path of local names downwards.
     *
     * <p>A {@code null} step stops the walk, so a caller that passes a possibly missing element gets
     * {@code null} back instead of an exception.</p>
     */
    private static Element descendant(Element parent, String... path) {
        Element current = parent;
        for (String step : path) {
            if (current == null) {
                return null;
            }
            current = child(current, step);
        }
        return current;
    }

    /** The text of the first element found along the path. */
    private static String text(Element parent, String... path) {
        Element element = descendant(parent, path);
        if (element == null) {
            return null;
        }
        String value = element.getTextContent();
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * The text of the first of several possible direct children.
     *
     * <p>Different from {@link #text}, which walks a path: a credit note carries a {@code CreditedQuantity}
     * where an invoice carries an {@code InvoicedQuantity}, and both mean the same field. Confusing the
     * two helpers silently produces a missing value, which is how this was found.</p>
     */
    private static String eitherText(Element parent, String... names) {
        for (String name : names) {
            String value = text(parent, name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String attribute(Element parent, String childName, String secondName, String attribute) {
        Element element = child(parent, childName);
        if (element == null) {
            element = child(parent, secondName);
        }
        if (element == null) {
            return null;
        }
        String direct = element.getAttribute(attribute);
        if (direct != null && !direct.isBlank()) {
            return direct;
        }
        // Read by local name as well: the qualified name of the same attribute differs between
        // revisions of the standard, and the quantity unitCode is worth finding in both.
        org.w3c.dom.NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node node = attributes.item(i);
            if (attribute.equalsIgnoreCase(localName(node))) {
                String value = node.getNodeValue();
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }

    private static BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.strip().replace(",", "."));
        }
        catch (NumberFormatException ex) {
            return null;
        }
    }

    private static LocalDate date(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.strip().substring(0, 10));
        }
        catch (RuntimeException ex) {
            return null;
        }
    }

    /** CII stores dates as {@code 20260301} inside a formatted element. */
    private static LocalDate dateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip();
        if (stripped.matches("\\d{8}")) {
            return LocalDate.parse(stripped.substring(0, 4) + "-" + stripped.substring(4, 6) + "-"
                    + stripped.substring(6, 8));
        }
        return date(stripped);
    }

    /** A supplier name is in different places depending on which party element exists. */
    private static String partyName(Element party) {
        if (party == null) {
            return null;
        }
        String name = text(descendant(party, "PartyName"), "Name");
        if (name != null) {
            return name;
        }
        name = text(descendant(party, "PartyLegalEntity"), "RegistrationName");
        return name != null ? name : text(party, "Name");
    }

    private static String partyVatId(Element party) {
        if (party == null) {
            return null;
        }
        return text(descendant(party, "PartyTaxScheme"), "CompanyID");
    }

    /** Turns a profile identifier into something a person recognises, without inventing one. */
    private static String profileOf(String customizationId) {
        if (customizationId == null) {
            return null;
        }
        String lower = customizationId.toLowerCase(Locale.ROOT);
        if (lower.contains("xrechnung")) {
            return "XRECHNUNG";
        }
        if (lower.contains("factur-x") || lower.contains("zugferd")) {
            return "ZUGFERD";
        }
        return customizationId;
    }

    /**
     * Top level elements the platform does not read.
     *
     * <p>Reported rather than dropped: a user comparing the platform's record with the file should see
     * what was not looked at, and a future version has a list of what to add.</p>
     */
    private static List<String> unrecognised(Element root, List<String> known) {
        List<String> unknown = new ArrayList<>();
        NodeList nodes = root.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            String name = localName(node);
            if (!known.contains(name)) {
                unknown.add(name);
            }
        }
        return unknown;
    }

    /** Convenience for callers that only want to know which syntax a file is. */
    public static Optional<Syntax> detectSyntax(byte[] xml) {
        try {
            return Optional.of(parse(xml).syntax());
        }
        catch (IOException ex) {
            return Optional.empty();
        }
    }
}
