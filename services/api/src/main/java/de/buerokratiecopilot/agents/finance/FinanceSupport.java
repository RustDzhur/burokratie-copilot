package de.buerokratiecopilot.agents.finance;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Small shared helpers for the finance agents: deterministic parsing and formatting of money, dates and
 * identifiers. Everything here is pure rule work — no external service, no network.
 */
final class FinanceSupport {

    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00",
            DecimalFormatSymbols.getInstance(Locale.GERMANY));

    private FinanceSupport() {
    }

    /** First non-blank value, stripped; {@code null} when there is none. */
    static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return null;
    }

    /**
     * Parses a money amount in the German forms found in invoice text: {@code 1.234,56}, {@code 96,00},
     * {@code 1190} — and tolerates the US form {@code 1190.00}.
     */
    static BigDecimal parseAmount(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.replace("€", "").replace("EUR", "").replace("eur", "").replaceAll("\\s", "")
                .strip();
        if (s.isEmpty() || s.equals("-")) {
            return null;
        }
        try {
            boolean hasComma = s.contains(",");
            if (hasComma) {
                if (s.lastIndexOf(',') > s.lastIndexOf('.')) {
                    s = s.replace(".", "").replace(',', '.');
                }
                else {
                    s = s.replace(",", "");
                }
            }
            else if (s.contains(".") && s.matches("-?\\d{1,3}(\\.\\d{3})+")) {
                s = s.replace(".", "");
            }
            return new BigDecimal(s);
        }
        catch (NumberFormatException e) {
            return null;
        }
    }

    /** Parses an ISO date ({@code yyyy-MM-dd}) or a German date ({@code dd.MM.yyyy}, {@code dd.MM.yy}). */
    static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.strip();
        try {
            return LocalDate.parse(s);
        }
        catch (DateTimeException ignored) {
            // try the German form below
        }
        String[] parts = s.split("\\.");
        if (parts.length == 3) {
            try {
                int day = Integer.parseInt(parts[0]);
                int month = Integer.parseInt(parts[1]);
                int year = Integer.parseInt(parts[2]);
                if (year < 100) {
                    year += year >= 70 ? 1900 : 2000;
                }
                return LocalDate.of(year, month, day);
            }
            catch (DateTimeException | NumberFormatException ignored) {
                // not a date
            }
        }
        return null;
    }

    /** A {@code date} column value as {@link LocalDate}, whatever driver type it came back as. */
    static LocalDate toLocalDate(Object value) {
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        if (value instanceof LocalDate date) {
            return date;
        }
        return null;
    }

    /** A nullable numeric column value as {@link BigDecimal}, {@code 0} when absent. */
    static BigDecimal nvl(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return new BigDecimal(String.valueOf(value));
    }

    /** Whether two amounts agree within the given tolerance. */
    static boolean close(BigDecimal left, BigDecimal right, BigDecimal tolerance) {
        return left.subtract(right).abs().compareTo(tolerance) <= 0;
    }

    /** German display form for money, e.g. {@code 1.234,56}. */
    static String money(BigDecimal value) {
        return MONEY.format(value == null ? BigDecimal.ZERO : value);
    }

    static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        String s = text.strip().replace('\n', ' ');
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }

    static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        }
        catch (IllegalArgumentException e) {
            return null;
        }
    }

    static List<UUID> parseUuids(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<UUID> ids = new ArrayList<>();
        for (String part : raw.split(",")) {
            UUID id = parseUuid(part);
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    static int parseInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        }
        catch (NumberFormatException e) {
            return fallback;
        }
    }
}
