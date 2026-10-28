package de.buerokratiecopilot.documents.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies text extraction per format against real files produced by the same libraries that read
 * them (spec §6.1, §9.1).
 *
 * <p>The interesting cases are not the happy paths but the ones the pipeline has to act on: a PDF
 * without a text layer must be recognised as needing OCR, a Windows-1252 file must not lose its
 * umlauts, and an XML file must not be able to read anything from the server.</p>
 */
class TextExtractorTest {

    private final PdfTextExtractor pdf = new PdfTextExtractor();
    private final DocxTextExtractor docx = new DocxTextExtractor();
    private final SpreadsheetTextExtractor spreadsheet = new SpreadsheetTextExtractor();
    private final PlainTextExtractor plainText = new PlainTextExtractor();
    private final EmlTextExtractor eml = new EmlTextExtractor();
    private final XmlTextExtractor xml = new XmlTextExtractor();

    private static byte[] pdfWithText(int pages, String... pageTexts) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage();
                document.addPage(page);
                String text = i < pageTexts.length ? pageTexts[i] : "";
                if (!text.isEmpty()) {
                    try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                        content.beginText();
                        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        content.newLineAtOffset(50, 700);
                        content.showText(text);
                        content.endText();
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] emptyPdf(int pages) throws IOException {
        return pdfWithText(pages);
    }

    private static byte[] docxWithTable() throws IOException {
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("Anforderung fehlender Unterlagen");
            XWPFTable table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("Unterlage");
            table.getRow(0).getCell(1).setText("Frist");
            table.getRow(1).getCell(0).setText("Mietvertrag");
            table.getRow(1).getCell(1).setText("31.03.2026");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] xlsx() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Rechnungen");
            var row = sheet.createRow(0);
            row.createCell(0).setCellValue("Rechnungsnummer");
            row.createCell(1).setCellValue(1190.00);
            var second = sheet.createRow(1);
            second.createCell(0).setCellValue("2026-001");
            second.createCell(1).setCellValue(119.00);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] read(String value, Charset charset) {
        return value.getBytes(charset);
    }

    @Test
    @DisplayName("a password-protected PDF says so instead of failing unreadably")
    void aProtectedPdfSaysWhatToDo() throws Exception {
        // A protected invoice is normal to receive and impossible to read. The message has to name the
        // problem, because the alternative is a document that sits in quarantine for ever with nobody
        // knowing why.
        byte[] protectedPdf;
        try (PDDocument document = new PDDocument()) {
            document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            var policy = new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy(
                    "owner", "user", new org.apache.pdfbox.pdmodel.encryption.AccessPermission());
            policy.setEncryptionKeyLength(128);
            document.protect(policy);
            var out = new java.io.ByteArrayOutputStream();
            document.save(out);
            protectedPdf = out.toByteArray();
        }

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> pdf.extract(new java.io.ByteArrayInputStream(protectedPdf), protectedPdf.length))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("passwortgeschützt");
    }

    @Test
    @DisplayName("a PDF with a text layer is extracted page by page")
    void extractsPdfPageByPage() throws Exception {
        byte[] content = pdfWithText(2, "Erste Seite", "Zweite Seite");

        ExtractedText result = pdf.extract(new ByteArrayInputStream(content), content.length);

        assertThat(result.pages()).hasSize(2);
        assertThat(result.pages().get(0).text()).contains("Erste Seite");
        assertThat(result.pages().get(1).text()).contains("Zweite Seite");
        assertThat(result.method()).isEqualTo(ExtractedText.Method.TEXT_LAYER);
        assertThat(result.needsOcr()).isFalse();
        assertThat(result.totalCharacters()).isGreaterThan(20);
    }

    @Test
    @DisplayName("a PDF without a text layer is reported as needing OCR, not as empty success")
    void detectsScannedPdf() throws Exception {
        byte[] content = emptyPdf(3);

        ExtractedText result = pdf.extract(new ByteArrayInputStream(content), content.length);

        assertThat(result.pages()).hasSize(3);
        assertThat(result.needsOcr())
                .as("a purely scanned document must be routed to OCR")
                .isTrue();
        assertThat(result.emptyPageCount()).isEqualTo(3);
        assertThat(result.fullText()).isEmpty();
    }

    @Test
    @DisplayName("a partly scanned PDF reports exactly which pages are empty")
    void detectsMixedPdf() throws Exception {
        byte[] content = pdfWithText(3, "Digital", "", "Digital");

        ExtractedText result = pdf.extract(new ByteArrayInputStream(content), content.length);

        assertThat(result.needsOcr()).isFalse();
        assertThat(result.emptyPageCount()).isEqualTo(1);
        assertThat(result.pages().get(1).text()).isBlank();
    }

    @Test
    @DisplayName("a DOCX yields paragraphs and table cells, because German letters carry data in tables")
    void extractsDocxWithTables() throws Exception {
        byte[] content = docxWithTable();

        ExtractedText result = docx.extract(new ByteArrayInputStream(content), content.length);

        String text = result.fullText();
        assertThat(text).contains("Anforderung fehlender Unterlagen");
        assertThat(text).contains("Mietvertrag");
        assertThat(text).contains("31.03.2026");
        assertThat(result.diagnostics()).containsEntry("tables", 1);
    }

    @Test
    @DisplayName("a spreadsheet yields one section per sheet with formatted numbers")
    void extractsSpreadsheet() throws Exception {
        byte[] content = xlsx();

        ExtractedText result = spreadsheet.extract(new ByteArrayInputStream(content), content.length);

        assertThat(result.pages()).hasSize(1);
        assertThat(result.fullText()).contains("Rechnungsnummer");
        assertThat(result.diagnostics()).containsEntry("sheets", 1);
    }

    @Test
    @DisplayName("UTF-8 and Windows-1252 text keep their umlauts")
    void detectsTextEncoding() throws Exception {
        String german = "Beitragsbescheid für die Krankenkasse\nBetrag: 119,00 €";

        ExtractedText utf8 = plainText.extract(new ByteArrayInputStream(read(german, StandardCharsets.UTF_8)),
                german.getBytes(StandardCharsets.UTF_8).length);
        assertThat(utf8.fullText()).contains("für").contains("€");
        assertThat(utf8.diagnostics()).containsEntry("charset", "UTF-8");

        byte[] windows = read("Beitragsbescheid für die Krankenkasse", Charset.forName("windows-1252"));
        ExtractedText cp1252 = plainText.extract(new ByteArrayInputStream(windows), windows.length);
        assertThat(cp1252.fullText()).contains("für");
        assertThat(cp1252.diagnostics()).containsEntry("charset", "windows-1252");
    }

    @Test
    @DisplayName("an e-mail file yields headers, body text and the names of its attachments")
    void extractsEml() throws Exception {
        String message = """
                From: behoerde@example.de
                To: buerger@example.de
                Subject: Anforderung fehlender Unterlagen
                Date: Mon, 2 Mar 2026 10:00:00 +0100
                MIME-Version: 1.0
                Content-Type: multipart/mixed; boundary="Grenze"

                --Grenze
                Content-Type: text/plain; charset=UTF-8

                Bitte reichen Sie den Mietvertrag bis zum 31.03.2026 nach.
                --Grenze
                Content-Type: application/pdf; name="Merkblatt.pdf"
                Content-Disposition: attachment; filename="Merkblatt.pdf"
                Content-Transfer-Encoding: base64

                JVBERi0xLjcK
                --Grenze--
                """;

        byte[] content = message.getBytes(StandardCharsets.UTF_8);
        ExtractedText result = eml.extract(new ByteArrayInputStream(content), content.length);

        assertThat(result.fullText()).contains("Anforderung fehlender Unterlagen");
        assertThat(result.fullText()).contains("31.03.2026");
        assertThat(result.diagnostics().get("attachments").toString()).contains("Merkblatt.pdf");
    }

    @Test
    @DisplayName("HTML-only mail is reduced to text without executing anything")
    void stripsHtmlMail() {
        String html = "<html><body><p>Sehr geehrte Damen und Herren,</p>"
                + "<script>alert('x')</script><p>Betrag: 119,00 &euro;</p></body></html>";

        String text = EmlTextExtractor.stripMarkup(html);

        assertThat(text).contains("Sehr geehrte Damen und Herren");
        assertThat(text).doesNotContain("alert");
        assertThat(text).doesNotContain("<p>");
    }

    @Test
    @DisplayName("XML is walked with element names, and external entities are not resolved")
    void extractsXmlWithoutResolvingExternalEntities() throws Exception {
        String invoice = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Invoice><Number>2026-001</Number><Amount currency="EUR">119.00</Amount></Invoice>
                """;
        byte[] content = invoice.getBytes(StandardCharsets.UTF_8);
        ExtractedText result = xml.extract(new ByteArrayInputStream(content), content.length);

        assertThat(result.fullText()).contains("Number: 2026-001");
        assertThat(result.fullText()).contains("119.00");
        assertThat(result.method()).isEqualTo(ExtractedText.Method.STRUCTURED);

        // A document type declaration is refused outright: an untrusted file must not be able to
        // pull in anything from outside.
        String withDoctype = """
                <?xml version="1.0"?>
                <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <Invoice><Number>&xxe;</Number></Invoice>
                """;
        byte[] attack = withDoctype.getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> xml.extract(new ByteArrayInputStream(attack), attack.length))
                .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("each extractor declares the content types it handles")
    void extractorsDeclareTheirFormats() {
        assertThat(pdf.supports("application/pdf")).isTrue();
        assertThat(docx.supports("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .isTrue();
        assertThat(spreadsheet.supports("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .isTrue();
        assertThat(plainText.supports("text/csv")).isTrue();
        assertThat(eml.supports("message/rfc822")).isTrue();
        assertThat(xml.supports("application/xml")).isTrue();
        assertThat(xml.supports("image/png")).isFalse();

        assertThat(List.of(pdf.format(), docx.format(), eml.format()))
                .containsExactly("pdf", "docx", "eml");
    }
}
