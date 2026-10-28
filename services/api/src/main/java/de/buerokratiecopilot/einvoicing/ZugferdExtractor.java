package de.buerokratiecopilot.einvoicing;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.springframework.stereotype.Component;

/**
 * Finds the invoice XML embedded in a ZUGFeRD or Factur-X PDF (spec §14, D-005).
 *
 * <p>A ZUGFeRD file is a PDF that carries the same invoice twice: once as a printable view and once as
 * structured XML in an embedded file. Both must agree, and the PDF alone is not an electronic invoice —
 * the acceptance criterion of this task says so explicitly, and it exists because a platform that
 * treated any PDF as an e-invoice would silently lose the machine-readable half.</p>
 *
 * <p>Nothing is attached to the PDF and nothing is written into it: the XML is read out unchanged, which
 * is the other half of the acceptance criterion.</p>
 */
@Component
public class ZugferdExtractor {

    /** The name a ZUGFeRD file usually gives its XML, and the alternative Factur-X uses. */
    private static final List<String> XML_HINTS = List.of("zugferd", "factur-x", "xrechnung", "invoice");

    /** An embedded invoice, with the name it had in the PDF. */
    public record EmbeddedXml(String filename, byte[] content) {

        /** The bytes as text, for a caller that wants to look at them; the original stays untouched. */
        public String asText() {
            return new String(content, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /**
     * Extracts the first embedded XML that looks like an invoice.
     *
     * @return the XML, or empty when the PDF carries none — which means the file is a PDF and nothing
     *         more, not an electronic invoice
     */
    public Optional<EmbeddedXml> extractXml(byte[] pdf) throws IOException {
        if (pdf == null || pdf.length == 0) {
            return Optional.empty();
        }
        try (PDDocument document = Loader.loadPDF(pdf)) {
            List<String> names = new ArrayList<>();
            for (PDComplexFileSpecification specification : embeddedFiles(document)) {
                String filename = filenameOf(specification);
                names.add(filename);
                PDEmbeddedFile embedded = specification.getEmbeddedFile();
                if (embedded == null) {
                    continue;
                }
                byte[] content = embedded.toByteArray();
                if (EInvoiceParser.looksLikeXml(content) && looksLikeInvoice(filename, content)) {
                    return Optional.of(new EmbeddedXml(filename, content));
                }
            }
            // A ZUGFeRD file may name its attachment anything; the content decides. The names are kept
            // only for the diagnostics of a file that turned out to carry no invoice.
            return Optional.empty();
        }
    }

    /** The names of the embedded files, for a report about why a PDF is not an electronic invoice. */
    public List<String> embeddedFileNames(byte[] pdf) throws IOException {
        if (pdf == null || pdf.length == 0) {
            return List.of();
        }
        try (PDDocument document = Loader.loadPDF(pdf)) {
            List<String> names = new ArrayList<>();
            for (PDComplexFileSpecification specification : embeddedFiles(document)) {
                names.add(filenameOf(specification));
            }
            return names;
        }
    }

    /**
     * The embedded file specifications of a document.
     *
     * <p>Walked through the generic name tree: the typed accessor differs between PDFBox versions, and
     * an invoice reader that stops compiling on a library upgrade is not worth the shorter call.</p>
     */
    private List<PDComplexFileSpecification> embeddedFiles(PDDocument document) throws IOException {
        List<PDComplexFileSpecification> found = new ArrayList<>();
        var names = document.getDocumentCatalog().getNames();
        if (names == null) {
            return found;
        }
        var tree = names.getEmbeddedFiles();
        if (tree == null) {
            return found;
        }
        for (var entry : tree.getNames().entrySet()) {
            PDComplexFileSpecification value = entry.getValue();
            if (value != null) {
                found.add(value);
            }
        }
        return found;
    }

    private static String filenameOf(PDComplexFileSpecification specification) {
        String name = specification.getFilename();
        if (name == null) {
            name = specification.getFileUnicode();
        }
        return name == null ? "" : name;
    }

    /**
     * Whether an embedded XML is plausibly an invoice.
     *
     * <p>The name is a hint, not a decision: ZUGFeRD requires an XML and the content says whether it is
     * an invoice, so a file whose name gives nothing away is still accepted, and one whose name says
     * "invoice" but whose content is something else is not.</p>
     */
    private static boolean looksLikeInvoice(String filename, byte[] content) {
        if (EInvoiceParser.detectSyntax(content).isPresent()) {
            return true;
        }
        String lower = filename.toLowerCase(java.util.Locale.ROOT);
        return XML_HINTS.stream().anyMatch(lower::contains) && EInvoiceParser.looksLikeXml(content);
    }
}
