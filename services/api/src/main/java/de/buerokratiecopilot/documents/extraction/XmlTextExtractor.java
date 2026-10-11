package de.buerokratiecopilot.documents.extraction;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Extracts text nodes from an XML document (spec §6.1, §14.3).
 *
 * <p>The parser is configured defensively, and that is the point of this class rather than a detail:
 * an XML file is untrusted input, so DTDs, external entities and external schemas are all disabled.
 * Without those settings a crafted invoice could read files from the server or hang the parser while
 * resolving a reference to an external host (spec §21.2).</p>
 *
 * <p>Element names are kept in the output ({@code Tag: value}) because a structured e-invoice is
 * unreadable as a bare list of values, and the structured data itself is parsed by the dedicated
 * e-invoicing handlers of task D-005.</p>
 */
@Component
public class XmlTextExtractor implements TextExtractor {

    @Override
    public boolean supports(String contentType) {
        return "application/xml".equals(contentType) || "text/xml".equals(contentType);
    }

    @Override
    public String format() {
        return "xml";
    }

    @Override
    public ExtractedText extract(InputStream content, long sizeBytes) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(true);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(content);
            document.getDocumentElement().normalize();

            List<String> lines = new ArrayList<>();
            collect(document.getDocumentElement(), lines, 0);

            return ExtractedText.singleSection(String.join("\n", lines), ExtractedText.Method.STRUCTURED,
                    Map.of("rootElement", document.getDocumentElement().getNodeName(),
                            "sizeBytes", sizeBytes,
                            "lines", lines.size()));
        }
        catch (ParserConfigurationException | SAXException ex) {
            throw new IOException("The XML document could not be parsed: " + ex.getMessage(), ex);
        }
    }

    /** Depth-limited walk: a deeply nested document must not exhaust the stack. */
    private static void collect(Node node, List<String> lines, int depth) {
        if (node == null || depth > 100 || lines.size() > 20_000) {
            return;
        }
        if (node.getNodeType() == Node.ELEMENT_NODE) {
            NodeList children = node.getChildNodes();
            StringBuilder ownText = new StringBuilder();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child.getNodeType() == Node.TEXT_NODE) {
                    ownText.append(child.getNodeValue());
                }
            }
            String text = ownText.toString().strip().replaceAll("\\s+", " ");
            String indent = "  ".repeat(Math.min(depth, 10));
            if (!text.isEmpty()) {
                lines.add(indent + node.getNodeName() + ": " + text);
            }
            for (int i = 0; i < children.getLength(); i++) {
                collect(children.item(i), lines, depth + 1);
            }
        }
        else if (node.getNodeType() == Node.DOCUMENT_NODE) {
            collect(((Document) node).getDocumentElement(), lines, depth);
        }
    }
}
