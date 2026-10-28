package de.buerokratiecopilot.exports;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;

import de.buerokratiecopilot.exports.ElsterExportService.Row;

/**
 * Writes the ELSTER export as the semicolon-separated file a German accountant opens in Excel.
 *
 * <p>Decimal amounts use the German comma ({@code 119,00}) and the separator is the semicolon, because
 * the amounts themselves contain commas. The header is fixed and machine-readable; a tax adviser's macro
 * addresses the columns by name, so the order is part of the contract.</p>
 *
 * <p>Warnings are not part of the CSV: a row that carries one is still a row. The JSON form of the export
 * returns the warnings together with the rows.</p>
 */
public final class ElsterCsvWriter {

    private static final char SEPARATOR = ';';

    private ElsterCsvWriter() {
    }

    public static String write(ElsterExportService.Export export) {
        StringBuilder csv = new StringBuilder(256 + export.rows().size() * 160);
        csv.append(String.join(String.valueOf(SEPARATOR), ElsterExportService.CSV_HEADER)).append('\n');
        for (Row row : export.rows()) {
            csv.append(String.join(String.valueOf(SEPARATOR),
                            field(row.categoryCode()),
                            field(row.euerLine()),
                            field(row.euerKz()),
                            field(row.datevSkr03()),
                            field(row.datevSkr04()),
                            field(row.bookingKind() == null ? null : row.bookingKind().name()),
                            amount(row.netto()),
                            amount(row.ust()),
                            amount(row.brutto()),
                            row.vorsteuerAbziehbar() ? "true" : "false",
                            amount(row.bewirtungAbziehbar70()),
                            amount(row.bewirtungNichtAbziehbar30()),
                            field(row.documentId() == null ? null : row.documentId().toString()),
                            field(row.date() == null ? null : row.date().toString())))
                    .append('\n');
        }
        return csv.toString();
    }

    /** Just the column names, in the promised order — the contract of the CSV. */
    public static List<String> header() {
        return ElsterExportService.CSV_HEADER;
    }

    private static String amount(BigDecimal value) {
        if (value == null) {
            return "";
        }
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    /** Quotes only when the value could break the row; a quoted value gets its quotes doubled. */
    private static String field(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        boolean quote = value.indexOf(SEPARATOR) >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!quote) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    /** The German display form, exposed for callers that print a total next to the file. */
    public static String euro(BigDecimal value) {
        return value == null ? "" : String.format(Locale.GERMANY, "%,.2f", value);
    }
}
