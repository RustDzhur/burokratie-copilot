package de.buerokratiecopilot.agents.documents;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.buerokratiecopilot.documents.fields.FieldValueParser;

/**
 * The pattern rules of {@code doc-redact}: where personal data sits in a text.
 *
 * <p>Every finding carries its character range, so a consumer could black out exactly the value and
 * nothing else. IBAN candidates are verified with the ISO 13616 checksum before they count, which
 * keeps account numbers that merely look like one out of the plan. This class is pure (no services,
 * no I/O) so the rules are unit-testable on their own.</p>
 */
public final class RedactionPlanner {

    /** One position to redact. {@code page} 0 means the text has no page structure. */
    public record Finding(String type, int page, int start, int end, String value, String snippet) {
    }

    /**
     * IBAN: two letters, two check digits, groups of four, optionally a shorter final group
     * (the printed form "DE89 3704 0044 0532 0130 00" ends in two). The checksum decides.
     */
    private static final Pattern IBAN = Pattern.compile(
            "\\b[A-Z]{2}\\d{2}(?:\\s?[A-Z0-9]{4}){2,7}(?:\\s?[A-Z0-9]{1,3})?\\b");

    /** E-mail addresses, the common administrative spelling. */
    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");

    /**
     * Telephone numbers: German long-distance with separators, or +49/0049 country prefixes.
     * The digits between the groups keep area codes like 030 out of the match on their own.
     */
    private static final Pattern PHONE = Pattern.compile(
            "(?:\\+49|0049)[\\s\\-/(]?\\d{1,4}[\\s\\-)]?\\d{2,4}[\\s\\-]?\\d{1,8}"
                    + "|\\b0\\d{2,4}[\\s\\-/]\\d{3,8}(?:[\\s\\-/]\\d{1,6})?\\b");

    /**
     * Addresses: a German postcode followed by a city-like name. The name must start uppercase, so
     * random digit strings in running text do not count.
     */
    private static final Pattern ADDRESS = Pattern.compile(
            "\\b\\d{5}\\s+[A-ZÄÖÜ][A-Za-zäöüß]+(?:[\\s.\\-][A-ZÄÖÜ][A-Za-zäöüß]+){0,3}\\b");

    private static final int SNIPPET_MARGIN = 40;

    private RedactionPlanner() {
    }

    /**
     * Finds all redaction positions in the text of one page.
     *
     * <p>Matches are dropped when they lie inside an already accepted finding (an IBAN contains no
     * e-mail, but overlapping patterns do occur between phone numbers and postcodes), so the plan
     * never asks to black out the same span twice.</p>
     */
    public static List<Finding> find(String text, int page) {
        List<Finding> findings = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return findings;
        }
        addIban(text, page, findings);
        addEmail(text, page, findings);
        addPhone(text, page, findings);
        addAddress(text, page, findings);
        findings.sort((a, b) -> a.start() != b.start() ? Integer.compare(a.start(), b.start())
                : Integer.compare(a.end(), b.end()));
        return findings;
    }

    private static void addIban(String text, int page, List<Finding> findings) {
        Matcher matcher = IBAN.matcher(text);
        while (matcher.find()) {
            if (covered(findings, matcher.start(), matcher.end())) {
                continue;
            }
            FieldValueParser.Parsed parsed = FieldValueParser.parse(matcher.group(), "IBAN");
            if (parsed.valid()) {
                findings.add(finding("IBAN", page, matcher.start(), matcher.end(),
                        matcher.group(), text));
            }
        }
    }

    private static void addEmail(String text, int page, List<Finding> findings) {
        Matcher matcher = EMAIL.matcher(text);
        while (matcher.find()) {
            if (!covered(findings, matcher.start(), matcher.end())) {
                findings.add(finding("EMAIL", page, matcher.start(), matcher.end(),
                        matcher.group(), text));
            }
        }
    }

    private static void addPhone(String text, int page, List<Finding> findings) {
        Matcher matcher = PHONE.matcher(text);
        while (matcher.find()) {
            if (!covered(findings, matcher.start(), matcher.end())) {
                findings.add(finding("PHONE", page, matcher.start(), matcher.end(),
                        matcher.group(), text));
            }
        }
    }

    private static void addAddress(String text, int page, List<Finding> findings) {
        Matcher matcher = ADDRESS.matcher(text);
        while (matcher.find()) {
            if (covered(findings, matcher.start(), matcher.end())) {
                continue;
            }
            // A pure digit run like "12345 EUR" already counts as amount context, not as an address;
            // the city word requirement in the pattern is what keeps those out.
            findings.add(finding("ADDRESS", page, matcher.start(), matcher.end(),
                    matcher.group(), text));
        }
    }

    private static boolean covered(List<Finding> findings, int start, int end) {
        for (Finding finding : findings) {
            if (start < finding.end() && end > finding.start()) {
                return true;
            }
        }
        return false;
    }

    private static Finding finding(String type, int page, int start, int end, String value,
                                   String text) {
        int lineStart = Math.max(0, text.lastIndexOf('\n', start) + 1);
        int lineEnd = text.indexOf('\n', end);
        if (lineEnd < 0) {
            lineEnd = text.length();
        }
        String line = text.substring(lineStart, lineEnd).strip();
        String snippet = line.length() <= SNIPPET_MARGIN * 2 ? line
                : line.substring(Math.max(0, start - lineStart - SNIPPET_MARGIN),
                        Math.min(line.length(), end - lineStart + SNIPPET_MARGIN)).strip();
        return new Finding(type, page, start, end, value, snippet);
    }
}
