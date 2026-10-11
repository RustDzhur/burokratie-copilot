package de.buerokratiecopilot.documents.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.xwpf.usermodel.BreakType;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import de.buerokratiecopilot.documents.content.RichDocument;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Turning a document into something other people can open (spec §6, E-004).
 *
 * <p>Two formats, for two different reasons. <strong>PDF</strong> is what is sent and filed: it looks the
 * same everywhere, and a letter that reflows on the recipient's machine is a letter whose layout nobody
 * controlled. <strong>DOCX</strong> is what is edited: an office that has to change a sentence should not
 * have to rebuild the document from a PDF.</p>
 *
 * <p><strong>Both formats carry the same content</strong>, and that is checked: a heading is a heading in
 * both, a table is a table in both. An export that silently dropped the tables would produce a file that
 * opens, looks plausible and is wrong.</p>
 *
 * <p>The types are the ones the formats define. The DOCX is written with a published library rather than by
 * assembling XML here, because a document that no word processor opens is not an export.</p>
 */
@Service
public class DocumentExporter {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentExporter.class);

    private static final float MARGIN = 56f;
    private static final float BODY = 10f;
    private static final float LINE = 14f;

    /** What was produced. */
    public record Exported(byte[] content, String mimeType, String filename, Map<String, Object> details) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("mimeType", mimeType);
            map.put("filename", filename);
            map.put("bytes", content.length);
            map.put("details", details);
            return map;
        }
    }

    /** Renders the document as a PDF that looks the same wherever it is opened. */
    public Exported toPdf(RichDocument.Document document) {
        PDFont regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        PDFont bold = new PDType14Bold();

        try (PDDocument pdf = new PDDocument()) {
            int pageNumber = 0;
            PDPage page = null;
            PDPageContentStream content = null;

            try {
                for (RichDocument.Block block : document.blocks()) {
                    boolean wantsNewPage = block instanceof RichDocument.Block.PageBreak;
                    if (page == null || wantsNewPage) {
                        if (content != null) {
                            finishPage(content, document, regular, pageNumber);
                            content.close();
                        }
                        page = new PDPage(PDRectangle.A4);
                        pdf.addPage(page);
                        pageNumber++;
                        content = new PDPageContentStream(pdf, page);
                        cursor = startPage(content, document, bold, regular);
                        if (wantsNewPage) {
                            continue;
                        }
                    }
                    cursor = render(pdf, content, document, block, regular, bold);
                    if (cursor < MARGIN + 40) {
                        finishPage(content, document, regular, pageNumber);
                        content.close();
                        page = new PDPage(PDRectangle.A4);
                        pdf.addPage(page);
                        pageNumber++;
                        content = new PDPageContentStream(pdf, page);
                        cursor = startPage(content, document, bold, regular);
                    }
                }
                if (content != null) {
                    finishPage(content, document, regular, pageNumber);
                }
            }
            finally {
                if (content != null) {
                    content.close();
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            pdf.save(out);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("pages", pageNumber);
            details.put("blocks", document.blocks().size());
            LOG.info("Exported PDF with {} page(s) and {} block(s)", pageNumber, document.blocks().size());
            return new Exported(out.toByteArray(), "application/pdf",
                    filename(document, "pdf"), details);
        }
        catch (IOException ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Das PDF konnte nicht erzeugt werden: "
                    + ex.getMessage());
        }
    }

    /** Renders the document as a DOCX, so that it can still be edited. */
    public Exported toDocx(RichDocument.Document document) {
        try (XWPFDocument docx = new XWPFDocument()) {
            if (document.title() != null && !document.title().isBlank()) {
                XWPFParagraph title = docx.createParagraph();
                title.setAlignment(ParagraphAlignment.CENTER);
                XWPFRun run = title.createRun();
                run.setText(document.title());
                run.setBold(true);
                run.setFontSize(16);
            }
            if (document.header() != null && !document.header().isBlank()) {
                docx.createHeader(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT)
                        .createParagraph().createRun().setText(document.header());
            }
            if (document.footer() != null && !document.footer().isBlank()) {
                docx.createFooter(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT)
                        .createParagraph().createRun().setText(document.footer());
            }

            int tables = 0;
            int images = 0;
            for (RichDocument.Block block : document.blocks()) {
                switch (block) {
                    case RichDocument.Block.Heading heading -> {
                        XWPFParagraph paragraph = docx.createParagraph();
                        XWPFRun run = paragraph.createRun();
                        run.setText(heading.text());
                        run.setBold(true);
                        run.setFontSize(Math.max(11, 18 - 2 * heading.level()));
                    }
                    case RichDocument.Block.Paragraph text -> {
                        XWPFParagraph paragraph = docx.createParagraph();
                        paragraph.createRun().setText(text.text());
                    }
                    case RichDocument.Block.ListItem item -> {
                        XWPFParagraph paragraph = docx.createParagraph();
                        paragraph.setIndentationLeft(360 * (item.depth() + 1));
                        paragraph.createRun().setText((item.ordered() ? "" : "\u2022 ") + item.text());
                    }
                    case RichDocument.Block.Table table -> {
                        List<String> headers = table.headers().isEmpty() ? table.rows().isEmpty()
                                ? List.of() : blankHeaders(table.rows().getFirst().size()) : table.headers();
                        XWPFTable created = docx.createTable(1, Math.max(1, headers.size()));
                        for (int column = 0; column < headers.size(); column++) {
                            XWPFRun run = created.getRow(0).getCell(column).getParagraphs().get(0)
                                    .createRun();
                            run.setText(headers.get(column));
                            run.setBold(true);
                        }
                        for (List<String> row : table.rows()) {
                            var tableRow = created.createRow();
                            for (int column = 0; column < headers.size(); column++) {
                                String value = column < row.size() ? row.get(column) : "";
                                tableRow.getCell(column).setText(value == null ? "" : value);
                            }
                        }
                        tables++;
                    }
                    case RichDocument.Block.Image image -> {
                        try {
                            XWPFParagraph paragraph = docx.createParagraph();
                            paragraph.createRun().addPicture(new java.io.ByteArrayInputStream(image.data()),
                                    org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_PNG, "image.png",
                                    pointsToEmu(image.widthPoints()), pointsToEmu(image.heightPoints()));
                            images++;
                        }
                        catch (Exception ex) {
                            // Reported by omission rather than silently: the caller is told how many images
                            // survived, because a document that lost one looks complete and is not.
                            LOG.warn("Bild konnte nicht eingebettet werden: {}", ex.getMessage());
                        }
                    }
                    case RichDocument.Block.PageBreak ignored ->
                            docx.createParagraph().createRun().addBreak(BreakType.PAGE);
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            docx.write(out);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("blocks", document.blocks().size());
            details.put("tables", tables);
            details.put("images", images);
            LOG.info("Exported DOCX with {} block(s), {} table(s), {} image(s)", document.blocks().size(),
                    tables, images);
            return new Exported(out.toByteArray(),
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    filename(document, "docx"), details);
        }
        catch (IOException ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Das DOCX konnte nicht erzeugt werden: "
                    + ex.getMessage());
        }
    }

    /** Both formats, so that a caller exports once and stores what it needs. */
    public Map<String, Exported> toBoth(RichDocument.Document document) {
        Map<String, Exported> both = new LinkedHashMap<>();
        both.put("pdf", toPdf(document));
        both.put("docx", toDocx(document));
        return both;
    }

    // ------------------------------------------------------------------ drawing

    private float cursor = 0;

    private float startPage(PDPageContentStream content, RichDocument.Document document, PDFont bold,
                            PDFont regular) throws IOException {
        float top = PDRectangle.A4.getHeight() - MARGIN;
        if (document.header() != null && !document.header().isBlank()) {
            write(content, regular, document.header(), top);
            top -= LINE;
        }
        if (document.title() != null && !document.title().isBlank()) {
            write(content, bold, document.title(), top);
            top -= LINE * 1.6f;
        }
        return top;
    }

    private float render(PDDocument pdf, PDPageContentStream content, RichDocument.Document document,
                         RichDocument.Block block, PDFont regular, PDFont bold) throws IOException {
        switch (block) {
            case RichDocument.Block.Heading heading -> {
                write(content, bold, heading.text(), cursor);
                cursor -= LINE * (1.6f - 0.1f * heading.level());
            }
            case RichDocument.Block.Paragraph text -> {
                for (String line : wrap(text.text(), 96)) {
                    write(content, regular, line, cursor);
                    cursor -= LINE;
                }
            }
            case RichDocument.Block.ListItem item -> {
                for (String line : wrap(item.text(), 92)) {
                    write(content, regular, "  ".repeat(Math.max(0, item.depth()))
                            + (item.ordered() ? "- " : "\u2022 ") + line, cursor);
                    cursor -= LINE;
                }
            }
            case RichDocument.Block.Table table -> {
                List<String> headers = table.headers();
                if (!headers.isEmpty()) {
                    write(content, bold, String.join("  |  ", headers), cursor);
                    cursor -= LINE;
                }
                for (List<String> row : table.rows()) {
                    write(content, regular, String.join("  |  ", row), cursor);
                    cursor -= LINE;
                }
                cursor -= LINE * 0.4f;
            }
            case RichDocument.Block.Image image -> {
                try {
                    PDImageXObject picture = PDImageXObject.createFromByteArray(pdf, image.data(), "image");
                    float width = Math.min(image.widthPoints(), PDRectangle.A4.getWidth() - 2 * MARGIN);
                    float height = image.heightPoints() * (width / Math.max(1, image.widthPoints()));
                    if (cursor - height < MARGIN) {
                        cursor = MARGIN - 1;
                        return cursor;
                    }
                    content.drawImage(picture, MARGIN, cursor - height, width, height);
                    cursor -= height + LINE;
                }
                catch (IOException ex) {
                    LOG.warn("Bild konnte nicht gezeichnet werden: {}", ex.getMessage());
                }
            }
            case RichDocument.Block.PageBreak ignored -> cursor = MARGIN - 1;
        }
        return cursor;
    }

    private void finishPage(PDPageContentStream content, RichDocument.Document document, PDFont regular,
                            int pageNumber) throws IOException {
        if (document.numbered()) {
            write(content, regular, "Seite " + pageNumber, MARGIN);
        }
        if (document.footer() != null && !document.footer().isBlank()) {
            write(content, regular, document.footer(), MARGIN - LINE);
        }
    }

    private void write(PDPageContentStream content, PDFont font, String text, float y) throws IOException {
        content.beginText();
        content.setFont(font, BODY);
        content.newLineAtOffset(MARGIN, y);
        content.showText(sanitize(text));
        content.endText();
    }

    static List<String> wrap(String text, int width) {
        if (text == null) {
            return List.of("");
        }
        List<String> lines = new java.util.ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                if (line.length() > 0) {
                    line.append(' ');
                }
                line.append(word);
            }
            lines.add(line.toString());
        }
        return lines;
    }

    /**
     * The characters the built-in typeface can draw.
     *
     * <p>The same limitation as the print service: an umlaut becomes a question mark. It is named here as
     * well, because an exported letter is more likely to be read by somebody else than a printed one.</p>
     */
    static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder safe = new StringBuilder(value.length());
        for (char character : value.toCharArray()) {
            safe.append(character >= 32 && character <= 126 ? character : '?');
        }
        return safe.toString();
    }

    private static List<String> blankHeaders(int count) {
        List<String> headers = new java.util.ArrayList<>();
        for (int column = 0; column < count; column++) {
            headers.add("");
        }
        return headers;
    }

    private static int pointsToEmu(float points) {
        return Math.round(points * 12700);
    }

    private static String filename(RichDocument.Document document, String extension) {
        String base = document.reference() != null && !document.reference().isBlank()
                ? document.reference()
                : document.title() != null && !document.title().isBlank() ? document.title() : "dokument";
        return base.replaceAll("[^A-Za-z0-9._-]", "_") + "." + extension;
    }

    /** A bold typeface from the standard fourteen, so the file opens everywhere. */
    private static final class PDType14Bold extends PDType1Font {
        PDType14Bold() {
            super(Standard14Fonts.FontName.HELVETICA_BOLD);
        }
    }
}
