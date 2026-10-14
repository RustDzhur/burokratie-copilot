package de.buerokratiecopilot.contacts.identifiers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds identifiers in the text of a German letter: IBAN (only when its check digits are right), USt-IdNr., Steuernummer
 * (only behind its label, because a bare number with slashes is not recognisable), and e-mail addresses. What is found is
 * compared with the identifiers of the contacts after normalisation.
 */
public final class IdentifierExtractor {

    /** One identifier found in a text. */
    public record Found(IdentifierKind kind, String raw, String normalized, int position) {
    }

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
    private static final Pattern IBAN = Pattern.compile("\\b[A-Z]{2}[0-9]{2}(?:[ ]?[A-Z0-9]{4}){2,7}(?:[ ]?[A-Z0-9]{1,3})?\\b");
    private static final Pattern GERMAN_VAT = Pattern.compile("\\bDE[ .\\-]?[0-9]{3}[ .\\-]?[0-9]{3}[ .\\-]?[0-9]{3}\\b");
    private static final Pattern LABELLED_VAT = Pattern.compile(
            "(?i)(?:USt\\.?-?\\s?Id(?:\\.?\\s?-?Nr)?\\.?|Umsatzsteuer-?\\s?Identifikationsnummer|VAT\\s?(?:ID|No\\.?|number))\\s*[:.]?\\s*([A-Z]{2}[A-Z0-9 .\\-]{8,18})");
    private static final Pattern STEUERNUMMER = Pattern.compile(
            "(?i)(?:Steuer-?\\s?(?:nummer|nr)\\.?|St\\.?\\s?-?\\s?Nr\\.?)\\s*[:.]?\\s*([0-9][0-9/ ]{7,15}[0-9])");

    private IdentifierExtractor() {
    }

    public static List<Found> extract(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        // One entry per kind and normalised value; the first position wins.
        Map<String, Found> found = new LinkedHashMap<>();
        collect(found, EMAIL.matcher(text), IdentifierKind.EMAIL, 0, false);
        for (Matcher m = IBAN.matcher(text.toUpperCase(java.util.Locale.ROOT)); m.find(); ) {
            String normalized = IdentifierNormalizer.normalize(IdentifierKind.IBAN, m.group());
            // The pattern may swallow a few characters of the following word; shorten until the check digits hold.
            for (int length = normalized.length(); length >= 15; length--) {
                String candidate = normalized.substring(0, length);
                if (IdentifierNormalizer.isValidIban(candidate)) {
                    put(found, new Found(IdentifierKind.IBAN, text.substring(m.start(), Math.min(text.length(), m.end())).strip(),
                            candidate, m.start()));
                    break;
                }
            }
        }
        for (Matcher m = GERMAN_VAT.matcher(text); m.find(); ) {
            String normalized = IdentifierNormalizer.normalize(IdentifierKind.VAT_ID, m.group());
            if (IdentifierNormalizer.looksLikeVatId(normalized)) {
                put(found, new Found(IdentifierKind.VAT_ID, m.group(), normalized, m.start()));
            }
        }
        for (Matcher m = LABELLED_VAT.matcher(text); m.find(); ) {
            String candidate = IdentifierNormalizer.normalize(IdentifierKind.VAT_ID, m.group(1));
            // Trailing words glued to the id by the loose pattern are cut back to a valid shape.
            for (int length = candidate.length(); length >= 10; length--) {
                String part = candidate.substring(0, length);
                if (IdentifierNormalizer.looksLikeVatId(part)) {
                    put(found, new Found(IdentifierKind.VAT_ID, m.group(1).strip(), part, m.start(1)));
                    break;
                }
            }
        }
        collect(found, STEUERNUMMER.matcher(text), IdentifierKind.TAX_NUMBER, 1, true);
        return new ArrayList<>(found.values());
    }

    private static void collect(Map<String, Found> found, Matcher matcher, IdentifierKind kind, int group, boolean strip) {
        while (matcher.find()) {
            String raw = matcher.group(group);
            String normalized = IdentifierNormalizer.normalize(kind, raw);
            if (!normalized.isBlank()) {
                put(found, new Found(kind, strip ? raw.strip() : raw, normalized, matcher.start(group)));
            }
        }
    }

    private static void put(Map<String, Found> found, Found value) {
        found.putIfAbsent(value.kind() + ":" + value.normalized(), value);
    }
}
