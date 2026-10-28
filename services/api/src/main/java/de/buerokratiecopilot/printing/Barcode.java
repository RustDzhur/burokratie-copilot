package de.buerokratiecopilot.printing;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * The mark that ties a sheet of paper to a record (spec §8, G-004).
 *
 * <p>Two things are produced, and the choice between them is not a preference.</p>
 *
 * <p><strong>A QR code carries the identifier.</strong> It holds a small reference rather than the content,
 * because paper lives longer than identifiers and a code that carried the whole record would have to be
 * reissued whenever anything changed.</p>
 *
 * <p><strong>A Code 128 barcode carries the same reference in one dimension.</strong> Scanners in offices
 * read it more reliably across a wider range of printers and paper, and the office that already has one
 * should not have to buy another.</p>
 *
 * <p>The encoding comes from a published library rather than a table written here. A barcode is a
 * specification somebody else defined, and a table copied from memory produces marks that no scanner
 * accepts — which is worse than no mark at all, because the paper then looks filed.</p>
 */
public final class Barcode {

    private static final Logger LOG = LoggerFactory.getLogger(Barcode.class);

    private Barcode() {
    }

    /** A rendered mark: which modules are dark, row by row. */
    public record Mark(boolean[][] modules, int width, int height, String format) {

        public boolean isDark(int x, int y) {
            return modules[y][x];
        }

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("format", format);
            map.put("width", width);
            map.put("height", height);
            return map;
        }
    }

    /** A QR code for the reference. */
    public static Mark qr(String payload, int size) {
        return encode(payload, BarcodeFormat.QR_CODE, size, size);
    }

    /** A Code 128 barcode for the same reference. */
    public static Mark code128(String payload, int width, int height) {
        return encode(payload, BarcodeFormat.CODE_128, width, height);
    }

    /**
     * The reference printed on the sheet.
     *
     * <p>Short and prefixed, so that a person reading it out over the telephone and a scanner reading it
     * optically say the same thing.</p>
     */
    public static String referenceFor(String subjectType, java.util.UUID subjectId) {
        String prefix = switch (subjectType == null ? "" : subjectType.toLowerCase(java.util.Locale.ROOT)) {
            case "document" -> "DOC";
            case "invoice" -> "INV";
            case "case" -> "CASE";
            case "contract" -> "CON";
            case "task" -> "TASK";
            default -> "REF";
        };
        String compact = subjectId.toString().replace("-", "").substring(0, 12)
                .toUpperCase(java.util.Locale.ROOT);
        return "BC-" + prefix + "-" + compact;
    }

    private static Mark encode(String payload, BarcodeFormat format, int width, int height) {
        if (payload == null || payload.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Ohne Inhalt lässt sich nichts markieren");
        }
        try {
            Map<EncodeHintType, Object> hints = new java.util.EnumMap<>(EncodeHintType.class);
            // Wide margins: a mark printed against the edge of a sheet is one a scanner misses.
            hints.put(EncodeHintType.MARGIN, 2);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            BitMatrix matrix = new MultiFormatWriter().encode(payload, format, width, height, hints);

            boolean[][] modules = new boolean[matrix.getHeight()][matrix.getWidth()];
            for (int y = 0; y < matrix.getHeight(); y++) {
                for (int x = 0; x < matrix.getWidth(); x++) {
                    modules[y][x] = matrix.get(x, y);
                }
            }
            return new Mark(modules, matrix.getWidth(), matrix.getHeight(), format.name());
        }
        catch (ApiException ex) {
            throw ex;
        }
        catch (Exception ex) {
            // Reported rather than swallowed: a sheet that was printed without its mark looks filed and
            // is not, and that is discovered months later.
            LOG.warn("Markierung konnte nicht erzeugt werden: {}", ex.getMessage());
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Die Markierung konnte nicht erzeugt werden: " + ex.getMessage());
        }
    }
}
