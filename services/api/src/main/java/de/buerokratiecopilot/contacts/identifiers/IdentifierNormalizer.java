package de.buerokratiecopilot.contacts.identifiers;

import java.math.BigInteger;
import java.util.Locale;

/**
 * One spelling for each identifier, so that "DE 123 456 789" and "DE123456789" are the same VAT id and an IBAN written in
 * blocks of four is the IBAN written in one piece. German letters spell them every way imaginable; two contacts are the
 * same party only when an identifier matches after this normalisation, never because their names look alike.
 */
public final class IdentifierNormalizer {

    private IdentifierNormalizer() {
    }

    public static String normalize(IdentifierKind kind, String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.strip();
        return switch (kind) {
            case VAT_ID, IBAN -> value.replaceAll("[\\s.\\-]", "").toUpperCase(Locale.ROOT);
            case TAX_NUMBER -> value.replaceAll("[^0-9A-Za-z]", "").toUpperCase(Locale.ROOT);
            case EMAIL -> value.toLowerCase(Locale.ROOT);
            case DOMAIN -> value.toLowerCase(Locale.ROOT).replaceFirst("^(https?://)?(www\\.)?", "").replaceAll("[/\\s].*$", "");
            case PHONE -> normalizePhone(value);
            case EXTERNAL -> value.replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        };
    }

    /**
     * Phone numbers are written "+49 30 123456", "030/123456", "0049 (0)30 123456". They become "+4930123456". A number
     * without a country prefix is taken as a German one: the platform is built for German correspondence.
     */
    static String normalizePhone(String value) {
        String digits = value.replaceAll("\\(0\\)", "").replaceAll("[^0-9+]", "");
        if (digits.startsWith("00")) {
            digits = "+" + digits.substring(2);
        }
        else if (digits.startsWith("0")) {
            digits = "+49" + digits.substring(1);
        }
        return digits;
    }

    /** ISO 13616 check: the rearranged number, read as an integer, leaves the remainder 1 when divided by 97. */
    public static boolean isValidIban(String normalized) {
        if (normalized == null || normalized.length() < 15 || normalized.length() > 34
                || !normalized.matches("[A-Z]{2}[0-9]{2}[A-Z0-9]+")) {
            return false;
        }
        String rearranged = normalized.substring(4) + normalized.substring(0, 4);
        StringBuilder digits = new StringBuilder();
        for (char c : rearranged.toCharArray()) {
            digits.append(Character.isDigit(c) ? String.valueOf(c) : String.valueOf(c - 'A' + 10));
        }
        return new BigInteger(digits.toString()).mod(BigInteger.valueOf(97)).intValue() == 1;
    }

    /** German VAT ids have exactly nine digits; other countries are accepted by shape only (two letters, then 8 to 12 characters). */
    public static boolean looksLikeVatId(String normalized) {
        if (normalized == null) {
            return false;
        }
        if (normalized.startsWith("DE")) {
            return normalized.matches("DE[0-9]{9}");
        }
        return normalized.matches("[A-Z]{2}[A-Z0-9]{8,12}");
    }
}
