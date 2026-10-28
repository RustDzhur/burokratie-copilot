package de.buerokratiecopilot.expenses;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.documents.classification.RuleBasedClassifier;
import de.buerokratiecopilot.expenses.domain.ExpenseCategoryRule.RuleType;

/**
 * Assigns an expense category from the configured rules.
 *
 * <p>The score of a category is the probabilistic OR of its matched rules — {@code 1 - Π(1 - w)}. The
 * property that matters for automatic filing: one weak hint stays weak, while a sender and a matching
 * position text reinforce each other. A sum would let a long receipt with many incidental words win; an
 * average would let a single keyword decide. Neither is acceptable when the result is where a receipt is
 * filed.</p>
 *
 * <p>The engine is pure: it receives the catalogue and the rules, and touches neither the database nor a
 * document. That is what makes the rule behaviour testable without a Spring context and what keeps the
 * classification stage from doing hidden queries per rule.</p>
 *
 * <p>Text is folded with the same rules as the document classifier ({@link RuleBasedClassifier#fold}):
 * umlauts written out, case and separators levelled, Cyrillic left intact. A rule "würth" therefore also
 * matches OCR output that lost the umlaut, and the Russian synonyms of the catalogue work on receipts from
 * Russian-speaking suppliers without a second matching path.</p>
 */
@Component
public class ExpenseRuleEngine {

    private static final Logger log = LoggerFactory.getLogger(ExpenseRuleEngine.class);

    /** Text length a regular expression is applied to; the extraction stage must stay the cheapest one. */
    private static final int REGEX_TEXT_LIMIT = 200_000;

    /** A match in the first lines (the title and sender area of a receipt) says more than the same word deep in the body. */
    private static final int HEAD_CHARACTERS = 600;

    /** One category as the engine needs it: the code to match and the order to sort by. */
    public record Category(String code, String parentCode, boolean businessExpense, boolean requiresReview,
                           int sortOrder) {
    }

    /** One rule as the engine needs it; independent of JPA so a test can build it directly. */
    public record Rule(String categoryCode, RuleType type, String pattern, BigDecimal amountMin,
                       BigDecimal amountMax, double weight, int priority, List<String> requiresAll) {
    }

    /** What one rule saw, in words a person can check. */
    public record Evidence(String kind, String value, double weight) {

        public String describe() {
            return switch (kind) {
                case "SENDER" -> "Absender '%s'".formatted(value);
                case "FILENAME" -> "Dateiname '%s'".formatted(value);
                case "AMOUNT" -> value;
                default -> "Schlüsselwort '%s'".formatted(value);
            };
        }
    }

    /** A scored category with the evidence that produced the score. */
    public record Match(String categoryCode, double score, int priority, List<Evidence> evidence) {
    }

    /** The ranking and the amounts the engine read; the amounts are kept for diagnostics and the API. */
    public record Result(List<Match> ranked, List<BigDecimal> amounts) {

        public boolean isEmpty() {
            return ranked.isEmpty();
        }

        public Match best() {
            return ranked.isEmpty() ? null : ranked.getFirst();
        }

        public Match alternative() {
            return ranked.size() < 2 ? null : ranked.get(1);
        }

        public List<String> rationale() {
            Match best = best();
            if (best == null) {
                return List.of();
            }
            return best.evidence().stream().map(Evidence::describe).toList();
        }
    }

    public Result categorize(String text, String filename, String sender, List<Category> categories,
                             List<Rule> rules) {
        if (categories == null || categories.isEmpty() || rules == null || rules.isEmpty()) {
            return new Result(List.of(), ExpenseAmounts.extract(text));
        }
        String rawText = text == null ? "" : text;
        Folded folded = new Folded(rawText, RuleBasedClassifier.fold(rawText),
                RuleBasedClassifier.fold(abbreviate(rawText)), RuleBasedClassifier.fold(filename == null ? "" : filename),
                RuleBasedClassifier.fold(sender == null ? "" : sender));
        List<BigDecimal> amounts = ExpenseAmounts.extract(rawText);

        Map<String, Category> byCode = new LinkedHashMap<>();
        for (Category category : categories) {
            byCode.put(category.code(), category);
        }

        // Two rules that differ only in spelling ("Würth" and "wuerth") are one piece of evidence, not
        // two. Counting both would let a duplicated rule outweigh a real one, which is exactly what an
        // administrator importing a keyword list twice would cause.
        Map<String, Map<String, Hit>> hitsByCategory = new LinkedHashMap<>();
        for (Rule rule : rules) {
            if (!byCode.containsKey(rule.categoryCode())) {
                continue;
            }
            Evidence evidence = match(rule, folded, amounts);
            if (evidence != null) {
                String key = rule.type() + "|" + normalizedKey(rule);
                Map<String, Hit> hits = hitsByCategory.computeIfAbsent(rule.categoryCode(),
                        code -> new LinkedHashMap<>());
                Hit previous = hits.get(key);
                if (previous == null || evidence.weight() > previous.evidence().weight()) {
                    hits.put(key, new Hit(rule, evidence));
                }
            }
        }
        if (hitsByCategory.isEmpty()) {
            return new Result(List.of(), amounts);
        }

        List<Match> matches = new ArrayList<>(hitsByCategory.size());
        for (Map.Entry<String, Map<String, Hit>> entry : hitsByCategory.entrySet()) {
            List<Hit> hits = List.copyOf(entry.getValue().values());
            double score = combine(hits.stream().mapToDouble(hit -> hit.evidence().weight()).toArray());
            int priority = hits.stream().mapToInt(hit -> hit.rule().priority()).max().orElse(0);
            List<Evidence> evidence = hits.stream().map(Hit::evidence).toList();
            matches.add(new Match(entry.getKey(), score, priority, evidence));
        }
        matches.sort(ordering(byCode));
        return new Result(List.copyOf(matches), amounts);
    }

    /** Probabilistic OR: 1 - Π(1 - w). Public so the property can be tested without a catalogue. */
    public static double combine(double[] weights) {
        double remaining = 1.0;
        for (double weight : weights) {
            remaining *= (1.0 - weight);
        }
        return Math.max(0.0, Math.min(1.0, 1.0 - remaining));
    }

    private static Comparator<Match> ordering(Map<String, Category> byCode) {
        return (left, right) -> {
            int comparison = Double.compare(right.score(), left.score());
            if (comparison != 0) {
                return comparison;
            }
            comparison = Integer.compare(right.priority(), left.priority());
            if (comparison != 0) {
                return comparison;
            }
            comparison = Integer.compare(orderOf(byCode, left), orderOf(byCode, right));
            if (comparison != 0) {
                return comparison;
            }
            return left.categoryCode().compareTo(right.categoryCode());
        };
    }

    private static int orderOf(Map<String, Category> byCode, Match match) {
        Category category = byCode.get(match.categoryCode());
        return category == null ? Integer.MAX_VALUE : category.sortOrder();
    }

    private Evidence match(Rule rule, Folded folded, List<BigDecimal> amounts) {
        String rawPattern = rule.pattern() == null ? "" : rule.pattern();
        String needle = RuleBasedClassifier.fold(rawPattern);
        double weight = rule.weight();

        boolean hit;
        String kind;
        String value;
        try {
            switch (rule.type()) {
                case KEYWORD -> {
                    hit = !needle.isEmpty() && containsTerm(folded.text(), needle);
                    kind = "KEYWORD";
                    value = rawPattern;
                    if (hit && containsTerm(folded.head(), needle)) {
                        // The title area is where a receipt names what it is about; the same word in the
                        // small print is weaker evidence, not equally strong evidence.
                        weight = Math.min(0.99, weight + (1.0 - weight) * 0.25);
                    }
                }
                case REGEX -> {
                    String bounded = folded.raw().length() > REGEX_TEXT_LIMIT
                            ? folded.raw().substring(0, REGEX_TEXT_LIMIT) : folded.raw();
                    hit = Pattern.compile(rawPattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                            .matcher(bounded).find();
                    kind = "KEYWORD";
                    value = rawPattern;
                }
                case SENDER -> {
                    hit = !needle.isEmpty() && containsTerm(folded.sender(), needle);
                    kind = "SENDER";
                    value = rawPattern;
                }
                case FILENAME -> {
                    hit = !needle.isEmpty() && containsTerm(folded.filename(), needle);
                    kind = "FILENAME";
                    value = rawPattern;
                }
                case AMOUNT_AT_LEAST, AMOUNT_AT_MOST, AMOUNT_BETWEEN -> {
                    hit = amountMatches(rule, amounts);
                    kind = "AMOUNT";
                    value = amountLabel(rule);
                }
                default -> {
                    hit = false;
                    kind = "KEYWORD";
                    value = rawPattern;
                }
            }
        }
        catch (PatternSyntaxException ex) {
            // A broken pattern is a configuration error, not a document error: log it and carry on rather
            // than failing the whole document because one rule cannot compile.
            log.warn("Expense rule pattern '{}' is not a valid regular expression: {}", rawPattern,
                    ex.getDescription());
            return null;
        }
        if (!hit) {
            return null;
        }
        for (String required : rule.requiresAll()) {
            if (!folded.text().contains(RuleBasedClassifier.fold(required))) {
                return null;
            }
        }
        return new Evidence(kind, value, weight);
    }

    private static boolean amountMatches(Rule rule, List<BigDecimal> amounts) {
        if (amounts.isEmpty()) {
            return false;
        }
        // The largest amount decides: a receipt with a 10 EUR line and a 900 EUR total is not a small
        // purchase, and a GWG receipt whose total is 900 EUR is not an investment either.
        BigDecimal largest = amounts.stream().max(BigDecimal::compareTo).orElseThrow();
        return switch (rule.type()) {
            case AMOUNT_AT_LEAST -> rule.amountMin() != null && largest.compareTo(rule.amountMin()) >= 0;
            case AMOUNT_AT_MOST -> rule.amountMax() != null && largest.compareTo(rule.amountMax()) <= 0;
            case AMOUNT_BETWEEN -> rule.amountMin() != null && rule.amountMax() != null
                    && largest.compareTo(rule.amountMin()) >= 0 && largest.compareTo(rule.amountMax()) <= 0;
            default -> false;
        };
    }

    private static String amountLabel(Rule rule) {
        NumberFormat format = NumberFormat.getCurrencyInstance(Locale.GERMANY);
        return switch (rule.type()) {
            case AMOUNT_AT_LEAST -> "Betrag ab " + format.format(rule.amountMin());
            case AMOUNT_AT_MOST -> "Betrag bis " + format.format(rule.amountMax());
            default -> "Betrag zwischen " + format.format(rule.amountMin()) + " und "
                    + format.format(rule.amountMax());
        };
    }

    /**
     * Whether a term occurs in the text.
     *
     * <p>For short terms a plain {@code contains} is wrong: "OBI" would match "mobile" and "dm" would match
     * "administrator". Shops and abbreviations are exactly the short terms, so those are matched on word
     * boundaries. Longer terms are matched as substrings on purpose, so "werkzeug" finds "Werkzeugkoffer"
     * and "schraube" finds "Schraubenset".</p>
     */
    public static boolean containsTerm(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isEmpty()) {
            return false;
        }
        // Every term must start at a word boundary. A substring hit in the middle of another word is a
        // false positive with real consequences: "pacht" matched "Spachtel" and filed a hardware receipt
        // under land lease. Compounds are covered by their own rules ("handwerkzeug") or by a regex rule.
        boolean alphabetic = Character.isLetter(needle.charAt(0));
        int from = 0;
        while (from <= haystack.length() - needle.length()) {
            int index = haystack.indexOf(needle, from);
            if (index < 0) {
                return false;
            }
            boolean leftOk = !alphabetic || index == 0
                    || !Character.isLetterOrDigit(haystack.charAt(index - 1));
            if (leftOk) {
                int end = index + needle.length();
                boolean rightOk = needle.length() >= 4
                        || end >= haystack.length()
                        || !Character.isLetterOrDigit(haystack.charAt(end));
                if (rightOk) {
                    return true;
                }
            }
            from = index + 1;
        }
        return false;
    }

    /** The folded pattern of a rule, so two spellings of the same term share one key. */
    private static String normalizedKey(Rule rule) {
        return switch (rule.type()) {
            case AMOUNT_AT_LEAST, AMOUNT_AT_MOST, AMOUNT_BETWEEN -> "amount|"
                    + rule.amountMin() + "|" + rule.amountMax();
            default -> RuleBasedClassifier.fold(rule.pattern() == null ? "" : rule.pattern());
        };
    }

    private static String abbreviate(String value) {
        return value.length() > HEAD_CHARACTERS ? value.substring(0, HEAD_CHARACTERS) : value;
    }

    /** The text as the rules compare it: folded once, with the title area kept separately. */
    private record Folded(String raw, String text, String head, String filename, String sender) {
    }

    /** A rule that fired together with what it saw; the pair keeps the rule's priority available. */
    private record Hit(Rule rule, Evidence evidence) {
    }
}
