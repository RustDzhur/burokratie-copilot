package de.buerokratiecopilot.exports;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The CSV file of a DATEV export package (Kanzlei edition, §5.6).
 *
 * <p><strong>The format is provisional.</strong> The specification says in §12.1 that the exact field profile,
 * order and encoding are fixed together with the first Kanzlei in Appendix 1. They are not agreed. This writer
 * therefore produces a readable, reproducible working format - not a claim that DATEV will accept it. The first
 * line of every file says so, so a file that leaves the house cannot be mistaken for a final import.</p>
 *
 * <p><strong>Why these choices.</strong> The separator is {@code ;} and the encoding is UTF-8 with a BOM,
 * because that is what Excel opens without a manual import dialog and what the office can inspect before it is
 * sent to DATEV. Rows end with CRLF for the same reason. The amount is written with a decimal comma
 * ({@code 1234,56}) because the target profile is German; a missing amount stays empty rather than becoming
 * zero, because zero is a booking amount and "unknown" is not.</p>
 *
 * <p><strong>Escaping.</strong> A field containing {@code ;}, a quote or a line break is quoted and its quotes
 * are doubled (RFC 4180). Without that, a supplier named {@code Müller; Söhne} would silently shift every
 * following column by one - the kind of file that opens and is wrong.</p>
 */
public final class DatevCsvWriter {

    /** The warning that has to stay in every file until Appendix 1 is signed off. */
    public static final String PROVISIONAL_NOTE = "VORLÄUFIG / nicht mit der Kanzlei abgestimmt";

    /** Column order of the provisional profile. Kept in one place so the document and the file cannot drift. */
    public static final List<String> COLUMNS = List.of(
            "Belegnummer", "Belegdatum", "Mandant", "Lieferant", "Betrag", "Währung",
            "Sollkonto", "Habenkonto", "Steuerschlüssel");

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final String LINE_END = "\r\n";
    private static final char SEPARATOR = ';';
    private static final char BOM = '\uFEFF';

    private DatevCsvWriter() {
    }

    /** One booking row. {@code betrag} may be null when the voucher has no readable amount yet. */
    public record Row(String belegnummer, LocalDate belegdatum, String mandant, String lieferant,
                      BigDecimal betrag, String waehrung, String sollkonto, String habenkonto,
                      String steuerschluessel) {
    }

    /**
     * Renders the file: BOM, the provisional warning, the column header, then one line per booking row.
     *
     * <p>The warning is its own line and not a comment inside the header row: the header row must keep exactly
     * nine columns, otherwise every reader that splits on {@code ;} sees a tenth value.</p>
     */
    public static byte[] write(List<Row> rows) {
        StringBuilder csv = new StringBuilder(256 + rows.size() * 128);
        csv.append(BOM);
        csv.append(PROVISIONAL_NOTE).append(LINE_END);
        for (int column = 0; column < COLUMNS.size(); column++) {
            if (column > 0) {
                csv.append(SEPARATOR);
            }
            csv.append(COLUMNS.get(column));
        }
        csv.append(LINE_END);

        for (Row row : rows) {
            csv.append(escape(row.belegnummer())).append(SEPARATOR);
            csv.append(row.belegdatum() == null ? "" : DATE.format(row.belegdatum())).append(SEPARATOR);
            csv.append(escape(row.mandant())).append(SEPARATOR);
            csv.append(escape(row.lieferant())).append(SEPARATOR);
            csv.append(amount(row.betrag())).append(SEPARATOR);
            csv.append(escape(row.waehrung())).append(SEPARATOR);
            csv.append(escape(row.sollkonto())).append(SEPARATOR);
            csv.append(escape(row.habenkonto())).append(SEPARATOR);
            csv.append(escape(row.steuerschluessel())).append(LINE_END);
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** German decimal notation, without a thousands separator; empty when the amount is not known. */
    static String amount(BigDecimal value) {
        if (value == null) {
            return "";
        }
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    /** RFC 4180 quoting. Empty and null are both an empty field, never the text "null". */
    static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        boolean quote = value.indexOf(SEPARATOR) >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
        if (!quote) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
