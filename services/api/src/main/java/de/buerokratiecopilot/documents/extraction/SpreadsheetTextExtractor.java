package de.buerokratiecopilot.documents.extraction;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

/**
 * Extracts text from a spreadsheet (spec §6.1: XLSX, and the same parser reads XLS).
 *
 * <p>Every sheet becomes one section, because a sheet is the unit a person refers to. Numbers are
 * formatted with the cell's own format instead of the raw double, so an amount in a German
 * spreadsheet reads as {@code 1.190,00} and not as {@code 1190.0} — a reviewer comparing the
 * extraction with the file must see the same thing.</p>
 */
@Component
public class SpreadsheetTextExtractor implements TextExtractor {

    @Override
    public boolean supports(String contentType) {
        return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet".equals(contentType)
                || "application/vnd.ms-excel".equals(contentType);
    }

    @Override
    public String format() {
        return "spreadsheet";
    }

    @Override
    public ExtractedText extract(InputStream content, long sizeBytes) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(content)) {
            DataFormatter formatter = new DataFormatter(java.util.Locale.GERMANY);
            List<ExtractedText.Page> pages = new ArrayList<>();
            Map<String, Object> diagnostics = new LinkedHashMap<>();
            int section = 1;
            int totalRows = 0;

            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                StringBuilder text = new StringBuilder();
                int rows = 0;

                for (Row row : sheet) {
                    List<String> cells = new ArrayList<>();
                    for (Cell cell : row) {
                        cells.add(formatter.formatCellValue(cell).strip());
                    }
                    if (cells.stream().allMatch(String::isBlank)) {
                        continue;
                    }
                    text.append(String.join(" | ", cells)).append('\n');
                    rows++;
                }

                totalRows += rows;
                pages.add(new ExtractedText.Page(section++, text.toString().strip(), null));
                diagnostics.put("sheet" + sheetIndex, sheet.getSheetName() + " (" + rows + " rows)");
            }

            diagnostics.put("sheets", workbook.getNumberOfSheets());
            diagnostics.put("rows", totalRows);
            diagnostics.put("sizeBytes", sizeBytes);
            return ExtractedText.of(pages, ExtractedText.Method.TEXT_LAYER, diagnostics);
        }
    }
}
