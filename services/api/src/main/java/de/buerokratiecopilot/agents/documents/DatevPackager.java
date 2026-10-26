package de.buerokratiecopilot.agents.documents;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import de.buerokratiecopilot.documents.domain.DocumentExtraction;

/**
 * The rule engine of {@code doc-datev}: one document into one DATEV import line.
 *
 * <p>Fields are mapped by rule — the document date becomes the Belegdatum, the extracted amount
 * the Betrag, the document number the Belegnummer, the document type the account pair. The
 * package checksum is the SHA-256 over the joined lines, so a changed byte in any value changes
 * the checksum. Every line that could mislead bookkeeping carries a warning (missing or negative
 * amount, missing or future date); warnings never block the package, they travel with it.</p>
 */
public final class DatevPackager {

    /** One mapped line, before the separator joins it. */
    public record Line(String date, String number, String account, String counterAccount,
                       String amount, String bookingText, List<String> warnings) {
    }

    /** The finished package: the lines and the checksum that locks them together. */
    public record Package(List<Line> lines, String checksum) {

        public String csv(String separator) {
            StringBuilder csv = new StringBuilder();
            for (Line line : lines) {
                csv.append(join(separator, line.date(), line.number(), line.account(),
                        line.counterAccount(), line.amount(), line.bookingText())).append('\n');
            }
            return csv.toString();
        }

        public int warningCount() {
            return lines.stream().mapToInt(line -> line.warnings().size()).sum();
        }
    }

    /** Amount field keys that win when several amounts were extracted. */
    private static final List<String> AMOUNT_KEYS = List.of(
            "rechnungsbetrag", "bruttobetrag", "gesamtsumme", "betrag", "summe", "nettobetrag");

    private final String separator;
    private final Map<String, String> accounts;
    private final LocalDate today;

    public DatevPackager(String separator, Map<String, String> accounts, LocalDate today) {
        this.separator = separator;
        this.accounts = accounts;
        this.today = today;
    }

    /** Maps one document: its metadata plus its extracted values. */
    public Line mapDocument(String filename, String typeCode, LocalDate documentDate,
                            String documentNumber, List<DocumentExtraction> extractions) {
        List<String> warnings = new ArrayList<>();

        String date = documentDate == null ? null : documentDate.toString();
        if (date == null) {
            date = firstOfType(extractions, "DATE");
        }
        if (date == null) {
            warnings.add("Belegdatum fehlt");
        }
        else if (today != null && LocalDate.parse(date).isAfter(today)) {
            warnings.add("Belegdatum liegt in der Zukunft");
        }

        String amount = preferredAmount(extractions);
        if (amount == null) {
            warnings.add("Betrag fehlt");
        }
        else if (new BigDecimal(amount).signum() < 0) {
            warnings.add("Betrag ist negativ");
        }

        String number = documentNumber;
        if (number == null) {
            number = firstOfType(extractions, "REFERENCE");
        }
        if (number == null) {
            warnings.add("Belegnummer fehlt");
        }

        String account = accounts.getOrDefault(typeCode == null ? "" : typeCode,
                accounts.getOrDefault("default", "1000"));
        String counterAccount = accounts.getOrDefault(
                typeCode == null ? "counter-default" : "counter-" + typeCode,
                accounts.getOrDefault("counter-default", "1600"));

        return new Line(date, number, account, counterAccount, amount,
                filename == null ? "" : filename, warnings);
    }

    /** Packages several lines and seals them with a checksum over the exact bytes. */
    public Package packageLines(List<Line> lines) {
        List<Line> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparing(line -> line.date() == null ? "" : line.date()));
        String csv = new Package(sorted, "").csv(separator);
        try {
            String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(csv.getBytes(StandardCharsets.UTF_8)));
            return new Package(sorted, checksum);
        }
        catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 ist nicht verfügbar", ex);
        }
    }

    /** The amount that wins: the declared keys first, else any extracted amount. */
    private String preferredAmount(List<DocumentExtraction> extractions) {
        Map<String, String> byKey = new java.util.HashMap<>();
        for (DocumentExtraction extraction : extractions) {
            byKey.put(extraction.getFieldKey().toLowerCase(Locale.ROOT),
                    extraction.getNormalizedValue());
        }
        for (String key : AMOUNT_KEYS) {
            String value = byKey.get(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return firstOfType(extractions, "AMOUNT");
    }

    private String firstOfType(List<DocumentExtraction> extractions, String type) {
        for (DocumentExtraction extraction : extractions) {
            if (type.equalsIgnoreCase(extraction.getValueType())
                    && extraction.getNormalizedValue() != null
                    && !extraction.getNormalizedValue().isBlank()) {
                return extraction.getNormalizedValue();
            }
        }
        return null;
    }

    private static String join(String separator, String... parts) {
        StringBuilder joined = new StringBuilder();
        for (String part : parts) {
            if (!joined.isEmpty()) {
                joined.append(separator);
            }
            joined.append(part == null ? "" : part);
        }
        return joined.toString();
    }
}
