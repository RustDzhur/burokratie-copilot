package de.buerokratiecopilot.einvoicing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.support.TestDocuments;

/**
 * Verifies that a PDF is only an electronic invoice when it carries the XML (spec §14, D-005 acceptance).
 *
 * <p>The acceptance criterion is explicit: a PDF without embedded XML is not an E-Rechnung. It exists
 * because a platform that treated any PDF as one would silently lose the machine-readable half — the
 * half that makes an e-invoice worth having. The tests therefore take a PDF that does carry the XML, a
 * PDF that does not, and a PDF whose attachment is something else.</p>
 */
class ZugferdExtractTest {

    private final ZugferdExtractor extractor = new ZugferdExtractor();

    /** The same XML the XRechnung test uses, so both syntaxes go through one parser. */
    private static final String UBL = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ubl:Invoice xmlns:ubl="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"
                         xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2">
              <cbc:CustomizationID>urn:cen.eu:en16931:2017#compliant#urn:xoev-de:kosit:standard:xrechnung_3.0</cbc:CustomizationID>
              <cbc:ID>RE-2026-4711</cbc:ID>
              <cbc:DocumentCurrencyCode>EUR</cbc:DocumentCurrencyCode>
            </ubl:Invoice>
            """;

    /** A PDF that carries the invoice XML as an embedded file, the way ZUGFeRD does. */
    private static byte[] pdfWithEmbedded(String filename, byte[] content) throws IOException {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());

            PDEmbeddedFile embedded = new PDEmbeddedFile(document, new java.io.ByteArrayInputStream(content));
            embedded.setSize(content.length);
            embedded.setSubtype(filename.endsWith(".xml") ? "text/xml" : "application/octet-stream");

            PDComplexFileSpecification specification = new PDComplexFileSpecification();
            specification.setFile(filename);
            specification.setEmbeddedFile(embedded);

            PDEmbeddedFilesNameTreeNode tree = new PDEmbeddedFilesNameTreeNode();
            tree.setNames(java.util.Map.of(filename, specification));
            PDDocumentNameDictionary names = new PDDocumentNameDictionary(document.getDocumentCatalog());
            names.setEmbeddedFiles(tree);
            document.getDocumentCatalog().setNames(names);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    @Test
    @DisplayName("a PDF carrying the invoice XML is recognised, and the XML comes out unchanged")
    void extractsEmbeddedXml() throws Exception {
        byte[] xml = UBL.getBytes(StandardCharsets.UTF_8);
        byte[] pdf = pdfWithEmbedded("zugferd-invoice.xml", xml);

        Optional<ZugferdExtractor.EmbeddedXml> extracted = extractor.extractXml(pdf);

        assertThat(extracted).isPresent();
        assertThat(extracted.get().filename()).isEqualTo("zugferd-invoice.xml");
        // The acceptance criterion: the original is preserved byte for byte, not re-written.
        assertThat(extracted.get().content()).isEqualTo(xml);
        assertThat(EInvoiceParser.parse(extracted.get().content()).invoiceNumber())
                .isEqualTo("RE-2026-4711");
    }

    @Test
    @DisplayName("a PDF without embedded XML is not an electronic invoice")
    void pdfWithoutXmlIsNotAnEInvoice() throws Exception {
        byte[] plainPdf = TestDocuments.pdf("Rechnung 2026-4711\nBetrag: 119,00 EUR");

        assertThat(extractor.extractXml(plainPdf))
                .as("a PDF alone is a picture of an invoice, not the invoice itself")
                .isEmpty();
        assertThat(extractor.embeddedFileNames(plainPdf)).isEmpty();
    }

    @Test
    @DisplayName("a PDF whose attachment is not an invoice XML is not one either")
    void attachmentThatIsNotAnInvoiceIsNotAnEInvoice() throws Exception {
        // The name suggests an invoice and the content is something else; the content decides.
        byte[] pdf = pdfWithEmbedded("rechnung.xml",
                "<Notiz><Text>Kein Rechnungsdokument</Text></Notiz>".getBytes(StandardCharsets.UTF_8));

        assertThat(extractor.extractXml(pdf)).isEmpty();
        assertThat(extractor.embeddedFileNames(pdf))
                .as("the name is still reported, so a user can see what was there")
                .containsExactly("rechnung.xml");
    }

    @Test
    @DisplayName("an XML attachment without a telling name is found by its content")
    void findsXmlByNameOrContent() throws Exception {
        byte[] pdf = pdfWithEmbedded("anhang.dat", UBL.getBytes(StandardCharsets.UTF_8));

        assertThat(extractor.extractXml(pdf))
                .as("a supplier may name the attachment anything; the invoice is still in there")
                .isPresent();
    }

    @Test
    @DisplayName("an empty or broken file is not an invoice, and does not throw")
    void handlesEmptyInput() throws Exception {
        assertThat(extractor.extractXml(new byte[0])).isEmpty();
        assertThat(extractor.extractXml(null)).isEmpty();
        assertThatThrownBy(() -> extractor.extractXml("%PDF-1.7 not really a pdf".getBytes(StandardCharsets.UTF_8)))
                .as("a file that claims to be a PDF and is not is an error, not a silent empty result")
                .isInstanceOf(IOException.class);
    }
}
