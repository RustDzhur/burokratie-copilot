package de.buerokratiecopilot.documents.extraction;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

/**
 * Extracts text from a Word document (spec §6.1).
 *
 * <p>A DOCX has no pages — pagination only exists once a layout engine renders it — so the document
 * is reported as a single section rather than inventing page breaks. Table cells are included: in a
 * German administrative letter the required documents, deadlines and amounts are very often in a
 * table, and silently dropping them would remove the most important content.</p>
 */
@Component
public class DocxTextExtractor implements TextExtractor {

    @Override
    public boolean supports(String contentType) {
        return "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(contentType);
    }

    @Override
    public String format() {
        return "docx";
    }

    @Override
    public ExtractedText extract(InputStream content, long sizeBytes) throws IOException {
        try (XWPFDocument document = new XWPFDocument(content)) {
            List<String> blocks = new ArrayList<>();
            int paragraphs = 0;
            int tables = 0;

            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    String text = paragraph.getText();
                    if (text != null && !text.isBlank()) {
                        blocks.add(text.strip());
                        paragraphs++;
                    }
                }
                else if (element instanceof XWPFTable table) {
                    blocks.add(renderTable(table));
                    tables++;
                }
            }

            String text = String.join("\n", blocks);
            ExtractedText result = ExtractedText.singleSection(text, ExtractedText.Method.TEXT_LAYER, Map.of(
                    "paragraphs", paragraphs,
                    "tables", tables,
                    "sizeBytes", sizeBytes,
                    "note", "A DOCX has no page layout, so the content is reported as one section"));
            return result;
        }
    }

    /** Renders a table row by row with cells separated by a pipe, as German invoices often are. */
    private static String renderTable(XWPFTable table) {
        StringBuilder builder = new StringBuilder();
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                cells.add(cell.getText() == null ? "" : cell.getText().strip().replace("\n", " "));
            }
            builder.append(String.join(" | ", cells)).append('\n');
        }
        return builder.toString().strip();
    }
}
