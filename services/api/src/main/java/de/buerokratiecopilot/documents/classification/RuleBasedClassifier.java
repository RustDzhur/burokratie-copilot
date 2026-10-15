package de.buerokratiecopilot.documents.classification;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.documents.DocumentTypeRuleRepository;
import de.buerokratiecopilot.documents.domain.DocumentTypeRule;

/**
 * Classifies a document from the configured rules (spec §9.3).
 *
 * <p>How the score is computed, and why: each matching rule contributes a weight between 0 and 1, and
 * the weights for one type are combined with a probabilistic OR —
 * {@code score = 1 - Π(1 - weight)}. Two independent hints of 0.6 therefore give 0.84, while a single
 * weak hint stays low. That property is what makes the automation threshold meaningful: a document
 * only reaches it when several pieces of evidence agree. An average would let one strong keyword
 * decide on its own, and a plain sum would let a long document with many weak matches win.</p>
 *
 * <p>Regex rules run against a bounded prefix of the text. A pattern from the configuration cannot be
 * malicious in itself, but a pathological one combined with a very long document could still take a
 * long time, and the extraction stage must never become the slowest part of the system.</p>
 */
@Component
public class RuleBasedClassifier implements DocumentClassifier {

    private static final Logger log = LoggerFactory.getLogger(RuleBasedClassifier.class);

    /** Text length a regex rule is applied to. */
    private static final int REGEX_TEXT_LIMIT = 200_000;

    private final DocumentTypeRuleRepository rules;

    public RuleBasedClassifier(DocumentTypeRuleRepository rules) {
        this.rules = rules;
    }

    @Override
    public String method() {
        return "RULE";
    }

    @Override
    public Classification classify(Input input) {
        List<DocumentTypeRule> effective = rules.findEffectiveRules(input.tenantId());
        if (effective.isEmpty()) {
            return Classification.none("No classification rules are configured");
        }

        Folded folded = Folded.of(input);
        Map<UUID, List<Match>> byType = new LinkedHashMap<>();
        for (DocumentTypeRule rule : effective) {
            Match match = matches(rule, input, folded);
            if (match != null) {
                byType.computeIfAbsent(rule.getDocumentTypeId(), key -> new ArrayList<>()).add(match);
            }
        }
        if (byType.isEmpty()) {
            return Classification.none("No configured rule matched this document");
        }

        record Score(UUID typeId, double score, List<Match> matches) {
        }

        List<Score> scores = byType.entrySet().stream()
                .map(entry -> new Score(entry.getKey(), combine(entry.getValue()), entry.getValue()))
                .sorted(Comparator.comparingDouble(Score::score).reversed())
                .toList();

        Score best = scores.getFirst();
        String rationale = "Matched %d rule(s) for this type: %s".formatted(best.matches().size(),
                String.join(", ", best.matches().stream().map(match -> match.rule().getPattern()).toList()));

        Classification.Alternative alternative = scores.size() > 1
                ? new Classification.Alternative(scores.get(1).typeId(), scores.get(1).score()) : null;
        return new Classification(best.typeId(), null, best.score(),
                best.matches().stream().map(match -> match.rule().getPattern()).toList(), rationale, alternative);
    }

    /** Probabilistic OR over the matched weights: 1 - Π(1 - w). */
    static double combine(List<Match> matches) {
        double remaining = 1.0;
        for (Match match : matches) {
            remaining *= (1.0 - match.weight());
        }
        return Math.max(0.0, Math.min(1.0, 1.0 - remaining));
    }

    /** The text in the form the keyword rules are compared in: umlauts and accents folded, case and separators levelled. */
    record Folded(String text, String head, String filename) {

        /** A match in the first lines (the title area of a letter) says more than the same word deep in the body. */
        static final int HEAD_CHARACTERS = 600;

        static Folded of(Input input) {
            String text = fold(input.text() == null ? "" : input.text());
            String head = text.length() > HEAD_CHARACTERS ? text.substring(0, HEAD_CHARACTERS) : text;
            return new Folded(text, head, fold(input.filename() == null ? "" : input.filename()));
        }
    }

    /**
     * Folds a text for comparison: lower case, ä/ö/ü/ß written out (ae, oe, ue, ss), other Latin accents dropped, hyphens and
     * underscores read as spaces, runs of white space as one space. OCR often loses the dots of an umlaut and people type
     * "Kuendigung"; both then meet the rule "kündigung". Cyrillic and other scripts are left as they are.
     */
    static String fold(String value) {
        String lowered = value.toLowerCase(Locale.GERMAN).replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
        String decomposed = Normalizer.normalize(lowered, Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder(decomposed.length());
        char previous = ' ';
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            if (Character.getType(c) == Character.NON_SPACING_MARK && previous >= 'a' && previous <= 'z') {
                continue; // an accent on a Latin letter; the mark on a Cyrillic letter is part of the letter
            }
            char mapped = (c == '-' || c == '_' || Character.isWhitespace(c)) ? ' ' : c;
            if (mapped == ' ' && previous == ' ') {
                continue;
            }
            out.append(mapped);
            previous = mapped;
        }
        return Normalizer.normalize(out.toString().strip(), Normalizer.Form.NFC);
    }

    private Match matches(DocumentTypeRule rule, Input input, Folded folded) {
        String pattern = rule.getPattern();
        String text = input.text() == null ? "" : input.text();
        String sender = input.senderEmail() == null ? "" : input.senderEmail();
        double weight = rule.weightAsDouble();

        try {
            boolean hit;
            switch (rule.getRuleType()) {
                case KEYWORD -> {
                    String needle = fold(pattern);
                    hit = !needle.isEmpty() && folded.text().contains(needle);
                    if (hit && folded.head().contains(needle)) {
                        weight = Math.min(0.99, weight + (1.0 - weight) * 0.25);
                    }
                }
                case REGEX -> {
                    String bounded = text.length() > REGEX_TEXT_LIMIT ? text.substring(0, REGEX_TEXT_LIMIT) : text;
                    hit = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                            .matcher(bounded).find();
                }
                case SENDER_DOMAIN -> {
                    String domain = pattern.toLowerCase(Locale.ROOT);
                    hit = sender.toLowerCase(Locale.ROOT).endsWith("@" + domain)
                            || sender.toLowerCase(Locale.ROOT).endsWith("." + domain);
                }
                case FILENAME -> hit = folded.filename().contains(fold(pattern));
                default -> hit = false;
            }
            if (!hit) {
                return null;
            }
            // A rule that names further terms counts only when every one of them is in the text as well.
            for (String required : rule.getRequiresAll()) {
                if (!folded.text().contains(fold(required))) {
                    return null;
                }
            }
            return new Match(rule, weight);
        }
        catch (PatternSyntaxException ex) {
            // A broken pattern is a configuration error, not a document error: report it and carry on
            // rather than failing the whole document.
            log.warn("The classification rule {} has an invalid regular expression: {}", rule.getId(),
                    ex.getMessage());
            return null;
        }
    }

    /** A matched rule together with what it matched, kept for the rationale. */
    record Match(DocumentTypeRule rule, double weight) {

        Match(DocumentTypeRule rule) {
            this(rule, rule.weightAsDouble());
        }
    }
}
