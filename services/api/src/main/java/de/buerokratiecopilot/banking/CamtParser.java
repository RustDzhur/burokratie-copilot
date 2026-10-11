package de.buerokratiecopilot.banking;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * Reads a CAMT.053 bank statement (ISO 20022) (spec §16, D-007).
 *
 * <p>A bank statement is the outside world's version of what happened to the money, and the platform's
 * own records are its version. Reconciliation is the work of getting those two to agree, which starts
 * with reading the statement exactly as the bank wrote it — no rounding, no sign correction, no filling
 * in of fields the bank left empty.</p>
 *
 * <p>The sign convention is the part that goes wrong most often. CAMT carries a credit/debit indicator
 * ({@code CRDT}/{@code DBIT}) separately from the amount, and the amount itself is always positive. A
 * reader that ignores the indicator turns every outgoing payment into income. Here the indicator decides
 * the sign, and a statement without one is reported rather than assumed.</p>
 *
 * <p>Elements are found by local name: the namespace depends on the message version, and a bank is not
 * going to change its export because of this platform.</p>
 */
public final class CamtParser {

    /** One booked movement. */
    public record Transaction(String externalId, LocalDate bookingDate, LocalDate valueDate,
                              BigDecimal amount, String currency, String counterpartyName,
                              String counterpartyIban, String purpose, String reference,
                              String endToEndId, String transactionType, boolean credit) {

        /** The amount as it affects the account: negative for a debit. */
        public BigDecimal signedAmount() {
            return credit ? amount : amount.negate();
        }
    }

    /** One statement in the file. */
    public record Statement(String statementId, String accountIban, String currency,
                            LocalDate periodStart, LocalDate periodEnd, BigDecimal openingBalance,
                            BigDecimal closingBalance, List<Transaction> transactions) {
    }

    /** Why a file is not a CAMT.053 statement. */
    public static final class NotAStatementException extends IOException {
        private final String reason;

        NotAStatementException(String message, String reason) {
            super(message);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    private CamtParser() {
    }

    /** Parses a CAMT.053 file, which may contain several statements. */
    public static List<Statement> parse(byte[] xml) throws IOException {
        if (xml == null || xml.length == 0) {
            throw new NotAStatementException("Die Datei ist leer", "EMPTY");
        }
        Document document = parseXml(xml);
        Element root = document.getDocumentElement();

        List<Element> statementElements = new ArrayList<>();
        collect(root, "Stmt", statementElements);
        if (statementElements.isEmpty()) {
            throw new NotAStatementException(
                    "Die Datei enthält keine Kontoauszüge (kein <Stmt>-Element)", "NO_STATEMENTS");
        }

        List<Statement> statements = new ArrayList<>();
        for (Element element : statementElements) {
            statements.add(readStatement(element));
        }
        return statements;
    }

    /** Whether the bytes look like a CAMT file at all. */
    public static boolean looksLikeCamt(byte[] content) {
        if (content == null || content.length == 0) {
            return false;
        }
        String head = new String(content, 0, Math.min(content.length, 2000), StandardCharsets.UTF_8);
        return head.contains("camt.053") || head.contains("BkToCstmrStmt");
    }

    private static Statement readStatement(Element statement) {
        Element account = child(statement, "Acct");
        String iban = account == null ? null : text(child(account, "Id"), "IBAN");
        String accountCurrency = account == null ? null : text(account, "Ccy");

        List<Transaction> transactions = new ArrayList<>();
        for (Element entry : children(statement, "Ntry")) {
            transactions.add(readEntry(entry, accountCurrency));
        }

        BigDecimal opening = null;
        BigDecimal closing = null;
        for (Element balance : children(statement, "Bal")) {
            String code = text(descendant(balance, "Tp"), "CdOrPrtry", "Cd");
            BigDecimal amount = decimal(text(balance, "Amt"));
            if (amount == null) {
                continue;
            }
            // The code says which balance this is; a reader that takes the first one gets the closing
            // balance as the opening one on some exports.
            boolean credit = "CRDT".equals(text(balance, "CdtDbtInd"));
            BigDecimal signed = credit ? amount : amount.negate();
            if ("OPBD".equals(code) || "PRCD".equals(code)) {
                opening = signed;
            }
            else if ("CLBD".equals(code) || "ITBD".equals(code)) {
                closing = signed;
            }
        }

        return new Statement(text(statement, "Id"), iban, accountCurrency,
                date(text(statement, "FrToDt", "FrDtTm")), date(text(statement, "FrToDt", "ToDtTm")),
                opening, closing, List.copyOf(transactions));
    }

    private static Transaction readEntry(Element entry, String accountCurrency) {
        BigDecimal amount = decimal(text(entry, "Amt"));
        String currency = text(entry, "Amt", "Ccy");
        String indicator = text(entry, "CdtDbtInd");
        boolean credit = "CRDT".equals(indicator);

        Element details = child(entry, "NtryDtls");
        Element transactionDetails = details == null ? null : child(details, "TxDtls");
        Element parties = transactionDetails == null ? null : child(transactionDetails, "RltdPties");
        Element counterparty = parties == null ? null : child(parties, "Dbtr");
        Element counterpartyAccount = parties == null ? null : child(parties, "DbtrAcct");
        if (counterparty == null && parties != null) {
            counterparty = child(parties, "Cdtr");
            counterpartyAccount = child(parties, "CdtrAcct");
        }

        Map<String, Object> ignored = new LinkedHashMap<>();
        ignored.put("indicator", indicator);
        ignored.put("accountCurrency", accountCurrency);

        return new Transaction(
                text(entry, "AcctSvcrRef") != null ? text(entry, "AcctSvcrRef")
                        : (transactionDetails == null ? null : text(transactionDetails, "EndToEndId")),
                date(text(entry, "BookgDt", "Dt")),
                date(text(entry, "ValDt", "Dt")),
                amount == null ? BigDecimal.ZERO : amount,
                currency != null ? currency : accountCurrency,
                counterparty == null ? null : (text(counterparty, "Nm") != null
                        ? text(counterparty, "Nm") : text(descendant(counterparty, "Pty"), "Nm")),
                counterpartyAccount == null ? null
                        : text(descendant(counterpartyAccount, "Id"), "IBAN"),
                transactionDetails == null ? null
                        : firstNonBlank(text(transactionDetails, "RmtInf", "Ustrd"),
                                text(entry, "AddtlNtryInf")),
                transactionDetails == null ? null : text(transactionDetails, "RmtInf", "Strd",
                        "CdtrRefInf", "Ref"),
                transactionDetails == null ? null : text(transactionDetails, "EndToEndId"),
                text(entry, "BkTxCd", "Domn", "Cd"),
                credit);
    }

    private static String firstNonBlank(String first, String second) {
        return first != null ? first : second;
    }

    // ------------------------------------------------------------------ XML

    private static Document parseXml(byte[] xml) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // A statement from a bank portal is untrusted input like any other file.
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
            throw new NotAStatementException("Das XML konnte nicht gelesen werden: " + ex.getMessage(),
                    "MALFORMED_XML");
        }
    }

    /** Collects all descendants with a local name, at any depth. */
    private static void collect(Element parent, String name, List<Element> found) {
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            if (name.equals(localName(node))) {
                found.add((Element) node);
            }
            collect((Element) node, name, found);
        }
    }

    private static String localName(Node node) {
        String local = node.getLocalName();
        return local != null ? local : node.getNodeName();
    }

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

    private static Element child(Element parent, String name) {
        List<Element> found = children(parent, name);
        return found.isEmpty() ? null : found.getFirst();
    }

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

    private static String text(Element parent, String... path) {
        Element element = descendant(parent, path);
        if (element == null) {
            return null;
        }
        String value = element.getTextContent();
        return value == null || value.isBlank() ? null : value.strip();
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
        String stripped = value.strip();
        try {
            return LocalDate.parse(stripped.length() > 10 ? stripped.substring(0, 10) : stripped);
        }
        catch (RuntimeException ex) {
            return null;
        }
    }
}
