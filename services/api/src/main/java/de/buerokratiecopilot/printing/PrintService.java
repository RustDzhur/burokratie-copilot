package de.buerokratiecopilot.printing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Printing on paper, and marking it so it can be found again (spec §8, G-004).
 *
 * <p>Printing is not an afterthought in an office platform: a great deal of German administrative work is
 * still done on paper, and a document that exists only on a screen in a house where the files are folders
 * is a document nobody can find.</p>
 *
 * <p><strong>Every page carries the mark.</strong> Not only the first, because pages get separated — that
 * is what happens to paper — and a page without a mark is a page that can no longer be attributed to
 * anything.</p>
 *
 * <p><strong>Content that does not fit starts a new page rather than being cut off.</strong> A printed
 * register that silently loses its last entries is worse than one that is longer, because nobody can tell
 * by looking at it.</p>
 *
 * <p><strong>The mark holds a reference, not the content.</strong> Paper outlives the data on it, and a
 * code carrying the whole record would have to be reprinted whenever anything changed.</p>
 */
@Service
public class PrintService {

    private static final Logger LOG = LoggerFactory.getLogger(PrintService.class);

    private static final float MARGIN = 50f;
    private static final float LINE_HEIGHT = 14f;
    private static final float FONT_SIZE = 9f;
    private static final float TITLE_SIZE = 14f;
    private static final float MARK_SIZE = 54f;

    /** What is printed on one page, and what it is about. */
    public record PrintRequest(String title, String subjectType, UUID subjectId, List<String> lines,
                               String footer) {
    }

    /** A register: several entries under one heading. */
    public record RegisterRequest(String title, String subjectType, UUID subjectId,
                                  List<String> columnHeaders, List<List<String>> rows, String footer) {
    }

    /** The printed document. */
    public record Printed(byte[] pdf, int pages, String reference) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("pages", pages);
            map.put("reference", reference);
            map.put("bytes", pdf.length);
            return map;
        }
    }

    /**
     * Renders a printable document.
     *
     * <p>The typeface is one of the fourteen every PDF reader has, deliberately: a sheet that prints as
     * empty boxes on somebody else's printer has failed at the only thing it was for.</p>
     */
    public Printed print(PrintRequest request) {
        if (request == null || request.subjectId() == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Ohne Bezug lässt sich nichts drucken");
        }
        String reference = Barcode.referenceFor(request.subjectType(), request.subjectId());
        Barcode.Mark mark = Barcode.qr(reference, 200);

        try (PDDocument document = new PDDocument()) {
            PDFont regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

            List<String> lines = request.lines() == null ? List.of() : request.lines();
            int pages = 0;
            int index = 0;
            boolean first = true;

            // At least one page, even for an empty document: paper with a heading and a mark is the record
            // that something was printed.
            do {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                pages++;

                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    float cursor = PDRectangle.A4.getHeight() - MARGIN;
                    cursor = heading(content, bold, regular, request, reference, cursor, first);

                    float bottom = MARGIN + MARK_SIZE + 20f;
                    while (index < lines.size() && cursor > bottom) {
                        cursor = writeLine(content, regular, lines.get(index++), cursor);
                    }

                    drawMark(content, mark, reference, regular);
                    pageFooter(content, regular, request.footer(), pages);
                }
                first = false;
            }
            while (index < lines.size());

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            LOG.info("Printed {} page(s) for {} ({})", pages, reference, request.subjectType());
            return new Printed(out.toByteArray(), pages, reference);
        }
        catch (IOException ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Das Dokument konnte nicht erzeugt werden: "
                    + ex.getMessage());
        }
    }

    /**
     * Renders a register: a heading, a table and a mark.
     *
     * <p>Rows that do not fit continue on the next page with the heading repeated, because a continuation
     * sheet without a heading is a sheet nobody can file.</p>
     */
    public Printed printRegister(RegisterRequest request) {
        if (request == null || request.rows() == null || request.rows().isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Ein Register ohne Zeilen wäre leer");
        }
        List<String> lines = new ArrayList<>();
        if (request.columnHeaders() != null) {
            lines.add(String.join("   |   ", request.columnHeaders()));
            lines.add("-".repeat(Math.min(90, Math.max(10,
                    String.join("   |   ", request.columnHeaders()).length()))));
        }
        for (List<String> row : request.rows()) {
            lines.add(String.join("   |   ", row));
        }
        return print(new PrintRequest(request.title(), request.subjectType(), request.subjectId(), lines,
                request.footer()));
    }

    /**
     * Renders one printable sheet per entry, which is what a stack of file covers needs.
     *
     * <p>Marking is per sheet, so a stack that gets shuffled can be put back together by scanning it.</p>
     */
    public List<Printed> printLabels(List<PrintRequest> labels) {
        if (labels == null || labels.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Es wurden keine Etiketten angegeben");
        }
        List<Printed> printed = new ArrayList<>();
        for (PrintRequest label : labels) {
            printed.add(print(label));
        }
        return printed;
    }

    // ------------------------------------------------------------------ drawing

    private float heading(PDPageContentStream content, PDFont bold, PDFont regular, PrintRequest request,
                          String reference, float cursor, boolean first) throws IOException {
        if (request.title() != null && !request.title().isBlank()) {
            content.beginText();
            content.setFont(bold, TITLE_SIZE);
            content.newLineAtOffset(MARGIN, cursor);
            content.showText(sanitize(request.title()));
            content.endText();
            cursor -= TITLE_SIZE + 8;
        }
        content.beginText();
        content.setFont(regular, FONT_SIZE);
        content.newLineAtOffset(MARGIN, cursor);
        content.showText("Bezug: " + reference + (first ? "" : " (Fortsetzung)"));
        content.endText();
        return cursor - LINE_HEIGHT - 6;
    }

    private float writeLine(PDPageContentStream content, PDFont font, String line, float cursor)
            throws IOException {
        content.beginText();
        content.setFont(font, FONT_SIZE);
        content.newLineAtOffset(MARGIN, cursor);
        content.showText(sanitize(line));
        content.endText();
        return cursor - LINE_HEIGHT;
    }

    private void drawMark(PDPageContentStream content, Barcode.Mark mark, String reference, PDFont font)
            throws IOException {
        float module = MARK_SIZE / mark.width();
        float originX = PDRectangle.A4.getWidth() - MARGIN - MARK_SIZE;
        float originY = MARGIN;

        content.setNonStrokingColor(0);
        for (int y = 0; y < mark.height(); y++) {
            for (int x = 0; x < mark.width(); x++) {
                if (mark.isDark(x, y)) {
                    content.addRect(originX + x * module, originY + (mark.height() - 1 - y) * module,
                            module, module);
                }
            }
        }
        content.fill();

        content.beginText();
        content.setFont(font, FONT_SIZE);
        content.newLineAtOffset(originX, originY - LINE_HEIGHT);
        content.showText(reference);
        content.endText();
    }

    private void pageFooter(PDPageContentStream content, PDFont font, String footer, int page)
            throws IOException {
        content.beginText();
        content.setFont(font, FONT_SIZE);
        content.newLineAtOffset(MARGIN, MARGIN - LINE_HEIGHT);
        content.showText(sanitize((footer == null ? "" : footer + "   ") + "Seite " + page));
        content.endText();
    }

    /**
     * Replaces the characters the built-in typeface cannot represent.
     *
     * <p>Without this, PDFBox throws on a single stray character and the whole sheet fails to print; a
     * document with one odd character in it still has to come out of the printer.</p>
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
}
