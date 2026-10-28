package de.buerokratiecopilot.expenses;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the amounts a German receipt states.
 *
 * <p>Why a dedicated reader: the difference between material and an investment is the amount, and a
 * receipt writes it the German way — {@code 1.234,56 €} — while the invoice number next to it looks almost
 * the same ({@code 20.10.2026}). Only a number followed by a currency marker counts, so a date or an
 * order number cannot become the amount that decides the category.</p>
 *
 * <p>Both orders occur ({@code 1.234,56 €} and {@code EUR 1.234,56}); both are read. Duplicates are
 * removed because a document repeats its total in the summary and in the payment line.</p>
 */
public final class ExpenseAmounts {

    private static final String NUMBER = "(\\d{1,3}(?:\\.\\d{3})+|\\d+)";
    private static final String DECIMALS = "(?:,(\\d{1,2}))?";

    /** Number before the currency: 1.234,56 €, 1234,56 EUR. */
    private static final Pattern AFTER = Pattern.compile(
            "(?<![\\d.,])" + NUMBER + DECIMALS + "\\s*(?:€|EUR|EURO)(?![A-Za-zÄÖÜäöü])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Currency before the number: EUR 1.234,56. */
    private static final Pattern BEFORE = Pattern.compile(
            "(?:€|EUR|EURO)\\s*" + NUMBER + DECIMALS + "(?![\\d.,])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private ExpenseAmounts() {
    }

    /** Every distinct amount in the order it appears; an empty list when the text states none. */
    public static List<BigDecimal> extract(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Set<BigDecimal> amounts = new LinkedHashSet<>();
        collect(AFTER, text, amounts);
        collect(BEFORE, text, amounts);
        return List.copyOf(new ArrayList<>(amounts));
    }

    /** The largest amount on the document, or {@code null} when it states none. */
    public static BigDecimal highest(String text) {
        return extract(text).stream().max(BigDecimal::compareTo).orElse(null);
    }

    private static void collect(Pattern pattern, String text, Set<BigDecimal> amounts) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String integer = matcher.group(1).replace(".", "");
            String decimals = matcher.group(2);
            try {
                BigDecimal value = new BigDecimal(integer + "." + (decimals == null ? "00" : pad(decimals)));
                if (value.signum() >= 0 && value.precision() <= 12) {
                    amounts.add(value);
                }
            }
            catch (NumberFormatException ex) {
                // A number the model cannot hold is not worth failing a classification over.
            }
        }
    }

    private static String pad(String decimals) {
        return decimals.length() == 1 ? decimals + "0" : decimals;
    }
}
