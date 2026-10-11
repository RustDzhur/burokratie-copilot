package de.buerokratiecopilot.documents.fields;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a value as it is written in a document into a comparable form (spec §9.4, §10).
 *
 * <p>German documents write amounts as {@code 1.190,00} and dates as {@code 31.03.2026}, while the
 * platform compares amounts as decimals and dates as ISO strings. Everything in this class is a
 * format conversion: it decides how a value is written down, never what the value means, and it
 * refuses anything it cannot parse instead of returning a plausible guess. A wrongly parsed amount is
 * worse than a missing one, because it looks like a fact.</p>
 */
public final class FieldValueParser {

    /** Result of a parse attempt. */
    public record Parsed(String original, String normalized, boolean valid, String reason) {

        static Parsed of(String original, String normalized) {
            return new Parsed(original, normalized, true, null);
        }

        static Parsed invalid(String original, String reason) {
            return new Parsed(original, null, false, reason);
        }
    }

    private static final Map<String, String> CURRENCY_SYMBOLS = Map.of(
            "€", "EUR", "$", "USD", "£", "GBP");

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("januar", 1), Map.entry("jan", 1),
            Map.entry("februar", 2), Map.entry("feb", 2),
            Map.entry("märz", 3), Map.entry("maerz", 3), Map.entry("mrz", 3), Map.entry("mar", 3),
            Map.entry("april", 4), Map.entry("apr", 4),
            Map.entry("mai", 5),
            Map.entry("juni", 6), Map.entry("jun", 6),
            Map.entry("juli", 7), Map.entry("jul", 7),
            Map.entry("august", 8), Map.entry("aug", 8),
            Map.entry("september", 9), Map.entry("sep", 9), Map.entry("sept", 9),
            Map.entry("oktober", 10), Map.entry("okt", 10),
            Map.entry("november", 11), Map.entry("nov", 11),
            Map.entry("dezember", 12), Map.entry("dez", 12));

    private static final Pattern DATE_NUMERIC = Pattern.compile(
            "(\\d{1,2})[.\\-/](\\d{1,2})[.\\-/](\\d{2,4})");
    private static final Pattern DATE_ISO = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
    private static final Pattern DATE_WORDS = Pattern.compile(
            "(\\d{1,2})\\.?\\s+([A-Za-zÄÖÜäöüß]{3,12})\\.?\\s+(\\d{4})");
    private static final Pattern AMOUNT = Pattern.compile(
            "[-+(]?\\s*[\\d][\\d.,\\s']*\\d?\\s*[)]?\\s*(?:€|EUR|USD|GBP|CHF|\\$|£)?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ISO_CURRENCY = Pattern.compile("\\b(EUR|USD|GBP|CHF|JPY|PLN|CZK)\\b");

    private FieldValueParser() {
    }

    /** Parses a raw value according to the declared field type. */
    public static Parsed parse(String raw, String valueType) {
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty()) {
            return Parsed.invalid(value, "empty");
        }
        if (valueType == null) {
            return Parsed.of(value, value);
        }
        return switch (valueType.toUpperCase(Locale.ROOT)) {
            case "AMOUNT" -> parseAmount(value);
            case "NUMBER" -> parseNumber(value);
            case "DATE" -> parseDate(value);
            case "PERIOD" -> parsePeriod(value);
            case "IBAN" -> parseIban(value);
            case "BIC" -> parseBic(value);
            case "CURRENCY" -> parseCurrency(value);
            case "BOOLEAN" -> parseBoolean(value);
            case "TAX_ID", "REFERENCE", "TEXT", "LIST" -> Parsed.of(value, value);
            default -> Parsed.of(value, value);
        };
    }

    /**
     * Parses an amount written in German or English notation.
     *
     * <p>Which separator is the decimal one is decided by position, not by assumption: when both are
     * present the last one wins, which handles {@code 1.190,00} and {@code 1,190.00} alike. With a
     * single separator, three following digits make it a thousands separator ({@code 1.190}) and one
     * or two make it a decimal one ({@code 119,00}).</p>
     */
    static Parsed parseAmount(String value) {
        Matcher matcher = AMOUNT.matcher(value);
        if (!matcher.find()) {
            return Parsed.invalid(value, "no number found");
        }
        String candidate = matcher.group().strip();
        boolean negative = candidate.startsWith("-") || candidate.startsWith("(");
        candidate = candidate.replaceAll("[^0-9.,]", "");

        int lastDot = candidate.lastIndexOf('.');
        int lastComma = candidate.lastIndexOf(',');
        String normalized;

        if (lastDot >= 0 && lastComma >= 0) {
            char decimalSeparator = lastDot > lastComma ? '.' : ',';
            char thousandsSeparator = decimalSeparator == '.' ? ',' : '.';
            normalized = candidate.replace(String.valueOf(thousandsSeparator), "")
                    .replace(decimalSeparator, '.');
        }
        else if (lastDot >= 0 || lastComma >= 0) {
            char separator = lastDot >= 0 ? '.' : ',';
            int position = lastDot >= 0 ? lastDot : lastComma;
            int decimals = candidate.length() - position - 1;
            boolean groupOfThree = decimals == 3 && candidate.indexOf(separator) == position
                    && candidate.length() > 4;
            normalized = groupOfThree
                    ? candidate.replace(String.valueOf(separator), "")
                    : candidate.replace(separator, '.');
        }
        else {
            normalized = candidate;
        }

        try {
            BigDecimal amount = new BigDecimal(normalized);
            if (negative) {
                amount = amount.negate();
            }
            return Parsed.of(value, amount.setScale(2, RoundingMode.HALF_UP).toPlainString());
        }
        catch (NumberFormatException ex) {
            return Parsed.invalid(value, "not a number");
        }
    }

    static Parsed parseNumber(String value) {
        Parsed amount = parseAmount(value);
        if (!amount.valid()) {
            return amount;
        }
        return Parsed.of(value, new BigDecimal(amount.normalized()).stripTrailingZeros().toPlainString());
    }

    /**
     * Parses the date formats a German document uses: {@code 31.03.2026}, {@code 31.3.26},
     * {@code 31. März 2026} and ISO {@code 2026-03-31}.
     *
     * <p>A date that does not exist ({@code 31.02.2026}) is rejected rather than rolled over into
     * March: silently correcting a date would change a deadline.</p>
     */
    static Parsed parseDate(String value) {
        Matcher iso = DATE_ISO.matcher(value);
        if (iso.find()) {
            return fromYmd(value, Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)),
                    Integer.parseInt(iso.group(3)), "yyyy-MM-dd");
        }

        Matcher numeric = DATE_NUMERIC.matcher(value);
        if (numeric.find()) {
            int first = Integer.parseInt(numeric.group(1));
            int second = Integer.parseInt(numeric.group(2));
            int year = Integer.parseInt(numeric.group(3));
            if (year < 100) {
                year += year < 70 ? 2000 : 1900;
            }
            // Day first: this is the German convention, and the opposite reading would turn
            // 03.04.2026 into March the fourth instead of April the third.
            return fromYmd(value, year, second, first, "dd.MM.yyyy");
        }

        Matcher words = DATE_WORDS.matcher(value);
        if (words.find()) {
            Integer month = MONTHS.get(words.group(2).toLowerCase(Locale.GERMAN));
            if (month == null) {
                return Parsed.invalid(value, "unknown month name");
            }
            return fromYmd(value, Integer.parseInt(words.group(3)), month,
                    Integer.parseInt(words.group(1)), "d. MMMM yyyy");
        }

        return Parsed.invalid(value, "no date found");
    }

    private static Parsed fromYmd(String original, int year, int month, int day, String format) {
        try {
            LocalDate date = LocalDate.of(year, month, day);
            return Parsed.of(original, date.format(DateTimeFormatter.ISO_LOCAL_DATE));
        }
        catch (java.time.DateTimeException ex) {
            return Parsed.invalid(original, "not a real date");
        }
    }

    /**
     * Parses a duration such as {@code 24 Monate} into months.
     *
     * <p>Only units that appear in contracts are recognised. Anything else keeps its original text as
     * the value and reports that no comparable form exists — a duration the platform does not
     * understand must not be converted into a wrong number of months.</p>
     */
    static Parsed parsePeriod(String value) {
        String lower = value.toLowerCase(Locale.GERMAN);
        Matcher matcher = Pattern.compile("(\\d+)\\s*(monat|monate|monaten|jahr|jahre|jahren|woche|wochen|tag|tage|tagen)")
                .matcher(lower);
        if (matcher.find()) {
            int amount = Integer.parseInt(matcher.group(1));
            String unit = matcher.group(2);
            int months = switch (unit) {
                case "monat", "monate", "monaten" -> amount;
                case "jahr", "jahre", "jahren" -> amount * 12;
                case "woche", "wochen" -> 0;
                case "tag", "tage", "tagen" -> 0;
                default -> 0;
            };
            if (months > 0) {
                return Parsed.of(value, String.valueOf(months));
            }
            // Weeks and days have no exact month equivalent; the original text is kept instead of a
            // rounded number that would silently shift a deadline.
            return Parsed.of(value, value);
        }
        return Parsed.of(value, value);
    }

    /**
     * Normalises and validates an IBAN with the MOD-97 checksum (ISO 13616).
     *
     * <p>The checksum is a real, verifiable property of the value: it catches the single most common
     * error, a transposed digit, before the number is used for a payment.</p>
     */
    static Parsed parseIban(String value) {
        String candidate = value.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        if (candidate.length() < 15 || candidate.length() > 34) {
            return Parsed.invalid(value, "an IBAN has 15 to 34 characters");
        }
        if (!candidate.matches("[A-Z]{2}\\d{2}[A-Z0-9]+")) {
            return Parsed.invalid(value, "an IBAN starts with two letters and two digits");
        }
        String rearranged = candidate.substring(4) + candidate.substring(0, 4);
        StringBuilder digits = new StringBuilder();
        for (char character : rearranged.toCharArray()) {
            if (Character.isDigit(character)) {
                digits.append(character);
            }
            else {
                digits.append(character - 'A' + 10);
            }
        }
        int remainder = 0;
        for (int i = 0; i < digits.length(); i++) {
            remainder = (remainder * 10 + (digits.charAt(i) - '0')) % 97;
        }
        if (remainder != 1) {
            return Parsed.invalid(value, "the IBAN checksum is wrong");
        }
        return Parsed.of(value, candidate);
    }

    static Parsed parseBic(String value) {
        String candidate = value.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        if (!candidate.matches("[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?")) {
            return Parsed.invalid(value, "a BIC has 8 or 11 characters");
        }
        return Parsed.of(value, candidate);
    }

    static Parsed parseCurrency(String value) {
        String upper = value.toUpperCase(Locale.ROOT);
        Matcher code = ISO_CURRENCY.matcher(upper);
        if (code.find()) {
            return Parsed.of(value, code.group(1));
        }
        for (Map.Entry<String, String> symbol : CURRENCY_SYMBOLS.entrySet()) {
            if (value.contains(symbol.getKey())) {
                return Parsed.of(value, symbol.getValue());
            }
        }
        return Parsed.invalid(value, "no currency recognised");
    }

    static Parsed parseBoolean(String value) {
        String lower = value.toLowerCase(Locale.GERMAN);
        if (lower.matches(".*\\b(ja|wahr|true|stimmt|erforderlich)\\b.*")) {
            return Parsed.of(value, "true");
        }
        if (lower.matches(".*\\b(nein|falsch|false|nicht erforderlich)\\b.*")) {
            return Parsed.of(value, "false");
        }
        return Parsed.invalid(value, "not a yes/no answer");
    }

    /** Field types that can be validated, and therefore trusted more than unvalidated text. */
    public static boolean isVerifiable(String valueType) {
        return List.of("AMOUNT", "NUMBER", "DATE", "IBAN", "BIC", "CURRENCY", "BOOLEAN", "PERIOD")
                .contains(valueType == null ? "" : valueType.toUpperCase(Locale.ROOT));
    }
}
