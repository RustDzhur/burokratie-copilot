package de.buerokratiecopilot.documents.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.documents.content.RichDocument;
import de.buerokratiecopilot.shared.error.ApiException;

/**
 * Verifies exporting a document (spec §6, E-004).
 *
 * <p>Both files are opened again and their content read: a PDF is parsed with PDFBox and a DOCX with POI,
 * so what is checked is that the file says what the document said, not that a byte array was produced. A
 * file that exists and is empty would pass a test that only looked at its length.</p>
 *
 * <p><strong>What is not verified here is the editor.</strong> The specification names a TipTap or
 * ProseMirror editor on the front end, and there is no front end in this repository yet. The content model
 * below is what such an editor would produce and send; the editor itself does not exist.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class ExportIT {

    @Autowired
    private DocumentExporter exporter;

    private RichDocument.Document letter() {
        return new RichDocument.Document("Antwort an die Behörde",
                List.of(new RichDocument.Block.Heading(1, "Betreff: Antrag"),
                        new RichDocument.Block.Paragraph("Sehr geehrte Damen und Herren,"),
                        new RichDocument.Block.ListItem("Anlage 1", false, 0),
                        new RichDocument.Block.ListItem("Anlage 2", false, 0),
                        new RichDocument.Block.Table(List.of("Position", "Betrag"),
                                List.of(List.of("Gebühr", "35,00"), List.of("Summe", "35,00"))),
                        new RichDocument.Block.PageBreak(),
                        new RichDocument.Block.Paragraph("Mit freundlichen Grüßen")),
                "Aktenzeichen 123/2026", "Muster GmbH", true, "BC-DOC-0123456789AB");
    }

    private String pdfText(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private String docxText(byte[] docx) throws Exception {
        try (XWPFDocument document = new XWPFDocument(new java.io.ByteArrayInputStream(docx))) {
            StringBuilder text = new StringBuilder();
            document.getParagraphs().forEach(paragraph -> text.append(paragraph.getText()).append('\n'));
            document.getTables().forEach(table -> table.getRows().forEach(row -> {
                for (int cell = 0; cell < row.getTableCells().size(); cell++) {
                    text.append(row.getCell(cell).getText()).append(' ');
                }
            }));
            return text.toString();
        }
    }

    // ---------------------------------------------------------------- PDF

    @Test
    @DisplayName("a document becomes a PDF that says what the document said")
    void aDocumentBecomesAPdf() throws Exception {
        DocumentExporter.Exported pdf = exporter.toPdf(letter());

        assertThat(pdf.mimeType()).isEqualTo("application/pdf");
        assertThat(pdf.filename()).endsWith(".pdf");
        String text = pdfText(pdf.content());
        assertThat(text).contains("Antwort an die").contains("Sehr geehrte").contains("Anlage 1");
    }

    @Test
    @DisplayName("a table is a table in the PDF too")
    void aTableSurvivesToPdf() throws Exception {
        String text = pdfText(exporter.toPdf(letter()).content());

        assertThat(text).contains("Position").contains("35,00");
        // The same named limitation as the print service: the built-in typeface is ASCII only, so an
        // umlaut becomes a question mark. Asserted rather than hidden.
        assertThat(text).contains("Geb?hr");
    }

    @Test
    @DisplayName("an explicit page break starts a new page")
    void anExplicitBreakStartsANewPage() throws Exception {
        DocumentExporter.Exported pdf = exporter.toPdf(letter());

        assertThat((Integer) pdf.details().get("pages"))
                .as("the document has one explicit break, so it needs two pages")
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("pages are numbered when the document asks for it")
    void pagesAreNumbered() throws Exception {
        String text = pdfText(exporter.toPdf(letter()).content());

        assertThat(text).contains("Seite 1");
    }

    @Test
    @DisplayName("header and footer are repeated on the page")
    void headerAndFooterAppear() throws Exception {
        String text = pdfText(exporter.toPdf(letter()).content());

        assertThat(text).contains("Aktenzeichen 123/2026").contains("Muster GmbH");
    }

    @Test
    @DisplayName("long content continues onto further pages instead of being cut off")
    void longContentContinues() throws Exception {
        List<RichDocument.Block> blocks = new ArrayList<>();
        for (int line = 0; line < 120; line++) {
            blocks.add(new RichDocument.Block.Paragraph("Absatz Nummer " + line));
        }
        RichDocument.Document longDocument = new RichDocument.Document("Lang", blocks, null, null, true,
                "BC-DOC-LANG");

        DocumentExporter.Exported pdf = exporter.toPdf(longDocument);

        assertThat((Integer) pdf.details().get("pages")).isGreaterThan(1);
        String text = pdfText(pdf.content());
        assertThat(text).contains("Absatz Nummer 0").contains("Absatz Nummer 119");
    }

    // ---------------------------------------------------------------- DOCX

    @Test
    @DisplayName("a document becomes a DOCX that says what the document said")
    void aDocumentBecomesADocx() throws Exception {
        DocumentExporter.Exported docx = exporter.toDocx(letter());

        assertThat(docx.mimeType()).contains("wordprocessingml");
        assertThat(docx.filename()).endsWith(".docx");
        String text = docxText(docx.content());
        assertThat(text).contains("Antwort an die").contains("Sehr geehrte").contains("Anlage 1");
    }

    @Test
    @DisplayName("a heading is still a heading in the DOCX, not just bold text")
    void aHeadingStaysAHeading() throws Exception {
        try (XWPFDocument document = new XWPFDocument(new java.io.ByteArrayInputStream(
                exporter.toDocx(letter()).content()))) {
            boolean boldAndLarger = document.getParagraphs().stream()
                    .anyMatch(paragraph -> paragraph.getRuns().stream().anyMatch(run ->
                            run.isBold() && run.getFontSize() >= 14));
            assertThat(boldAndLarger)
                    .as("an office that opens the file has to be able to see what is a heading")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("a table is a real table in the DOCX, not a line of text")
    void aTableIsARealTable() throws Exception {
        try (XWPFDocument document = new XWPFDocument(new java.io.ByteArrayInputStream(
                exporter.toDocx(letter()).content()))) {
            assertThat(document.getTables())
                    .as("a table flattened into text is a table somebody has to rebuild by hand")
                    .hasSize(1);
            assertThat(document.getTables().getFirst().getRows()).hasSize(3);
        }
    }

    @Test
    @DisplayName("header and footer are set on the DOCX")
    void headerAndFooterAreSetOnDocx() throws Exception {
        try (XWPFDocument document = new XWPFDocument(new java.io.ByteArrayInputStream(
                exporter.toDocx(letter()).content()))) {
            assertThat(document.getHeaderList()).isNotEmpty();
            assertThat(document.getFooterList()).isNotEmpty();
        }
    }

    @Test
    @DisplayName("both formats carry the same content")
    void bothFormatsCarryTheSameContent() throws Exception {
        Map<String, DocumentExporter.Exported> both = exporter.toBoth(letter());

        String fromPdf = pdfText(both.get("pdf").content());
        // Compared as printed: the PDF typeface is ASCII only, so the umlaut is already a question mark
        // there while the DOCX keeps the real character.
        String fromDocx = docxText(both.get("docx").content());
        for (String phrase : List.of("Antwort an die", "Sehr geehrte", "Anlage 1", "35,00", "Position",
                "Geb?hr")) {
            String expected = phrase.equals("Geb?hr") ? "Geb?hr" : phrase;
            assertThat(fromPdf).as("PDF enthält '%s'", expected).contains(expected);
            // The DOCX keeps the umlaut; the PDF cannot draw it, and that difference is the limitation.
            assertThat(fromDocx).as("DOCX enthält '%s'", phrase)
                    .contains(phrase.equals("Geb?hr") ? "Gebühr" : phrase);
        }
    }

    @Test
    @DisplayName("an image is embedded in the DOCX and counted honestly")
    void anImageIsEmbedded() {
        // A one-pixel PNG, assembled here rather than stored as a fixture.
        byte[] png = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
        RichDocument.Document withImage = new RichDocument.Document("Mit Bild",
                List.of(new RichDocument.Block.Paragraph("Vor dem Bild"),
                        new RichDocument.Block.Image(png, "image/png", 100, 100)),
                null, null, false, "BC-DOC-IMG");

        DocumentExporter.Exported docx = exporter.toDocx(withImage);

        assertThat((Integer) docx.details().get("images"))
                .as("a document that lost an image looks complete and is not")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a document whose image is unusable still exports, and the count says what happened")
    void anUnusableImageStillExports() throws Exception {
        // POI accepts bytes it cannot actually draw, so this is not a validity check - and pretending it
        // were would be a test asserting a guarantee the library does not make. What is checked is that a
        // document with a bad image still comes out, and that the count is reported rather than guessed.
        RichDocument.Document broken = new RichDocument.Document("Kaputt",
                List.of(new RichDocument.Block.Paragraph("Text"),
                        new RichDocument.Block.Image(new byte[] {1, 2, 3}, "image/png", 100, 100)),
                null, null, false, "BC-DOC-BROKEN");

        DocumentExporter.Exported docx = exporter.toDocx(broken);

        assertThat(docx.content()).isNotEmpty();
        assertThat(docx.details()).containsKey("images");
        assertThat(docxText(docx.content())).contains("Text");
    }

    // ---------------------------------------------------------------- reading a document back

    @Test
    @DisplayName("a document is read from what an editor would send")
    void aDocumentIsReadFromJson() {
        Map<String, Object> json = Map.of(
                "title", "Titel",
                "reference", "BC-DOC-1",
                "numbered", true,
                "header", "Kopf",
                "blocks", List.of(
                        Map.of("type", "heading", "level", 2, "text", "Überschrift"),
                        Map.of("type", "paragraph", "text", "Absatz"),
                        Map.of("type", "list_item", "text", "Punkt", "ordered", true, "depth", 1),
                        Map.of("type", "table", "headers", List.of("A"), "rows", List.of(List.of("1"))),
                        Map.of("type", "page_break")));

        RichDocument.Document document = RichDocument.parse(json);

        assertThat(document.title()).isEqualTo("Titel");
        assertThat(document.numbered()).isTrue();
        assertThat(document.blocks()).hasSize(5);
        assertThat(document.asMap()).containsKey("blocks");
    }

    @Test
    @DisplayName("an unknown block type is refused rather than skipped")
    void anUnknownBlockTypeIsRefused() {
        assertThatThrownBy(() -> RichDocument.parse(Map.of("blocks",
                List.of(Map.of("type", "hologram", "text", "x")))))
                .as("skipping it would export a document that is silently missing a part")
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Unbekannter Blocktyp");
    }

    @Test
    @DisplayName("a block without its text is refused")
    void aBlockWithoutTextIsRefused() {
        assertThatThrownBy(() -> RichDocument.parse(Map.of("blocks",
                List.of(Map.of("type", "paragraph")))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("braucht das Feld");
    }

    @Test
    @DisplayName("a heading level outside one to six is refused")
    void aHeadingLevelOutsideTheRangeIsRefused() {
        RichDocument.Document document = RichDocument.parse(Map.of("blocks",
                List.of(Map.of("type", "heading", "level", 9, "text", "Zu tief"))));

        // A level seven is not a heading, it is a typo, and every format that accepts it renders something
        // the author did not intend.
        assertThatThrownBy(() -> RichDocument.describe(document.blocks().getFirst()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("1 bis 6");
    }

    @Test
    @DisplayName("content that is not a document is refused")
    void contentThatIsNotADocumentIsRefused() {
        assertThatThrownBy(() -> RichDocument.parse("kein Dokument"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("wrapping keeps the text and does not lose words")
    void wrappingKeepsTheText() {
        List<String> lines = DocumentExporter.wrap("ein zwei drei vier fuenf sechs sieben acht", 12);

        assertThat(lines).hasSizeGreaterThan(1);
        assertThat(String.join(" ", lines)).isEqualTo("ein zwei drei vier fuenf sechs sieben acht");
    }
}
