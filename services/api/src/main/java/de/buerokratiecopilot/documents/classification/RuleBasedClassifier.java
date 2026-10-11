package de.buerokratiecopilot.documents.classification;

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

        Map<UUID, List<Match>> byType = new LinkedHashMap<>();
        for (DocumentTypeRule rule : effective) {
            if (matches(rule, input)) {
                byType.computeIfAbsent(rule.getDocumentTypeId(), key -> new ArrayList<>())
                        .add(new Match(rule));
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

        return new Classification(best.typeId(), null, best.score(),
                best.matches().stream().map(match -> match.rule().getPattern()).toList(), rationale);
    }

    /** Probabilistic OR over the matched weights: 1 - Π(1 - w). */
    static double combine(List<Match> matches) {
        double remaining = 1.0;
        for (Match match : matches) {
            remaining *= (1.0 - match.rule().weightAsDouble());
        }
        return Math.max(0.0, Math.min(1.0, 1.0 - remaining));
    }

    private boolean matches(DocumentTypeRule rule, Input input) {
        String pattern = rule.getPattern();
        String text = input.text() == null ? "" : input.text();
        String filename = input.filename() == null ? "" : input.filename();
        String sender = input.senderEmail() == null ? "" : input.senderEmail();

        try {
            return switch (rule.getRuleType()) {
                case KEYWORD -> text.toLowerCase(Locale.GERMAN).contains(pattern.toLowerCase(Locale.GERMAN));
                case REGEX -> {
                    String bounded = text.length() > REGEX_TEXT_LIMIT ? text.substring(0, REGEX_TEXT_LIMIT) : text;
                    yield Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                            .matcher(bounded).find();
                }
                case SENDER_DOMAIN -> {
                    String domain = pattern.toLowerCase(Locale.ROOT);
                    yield sender.toLowerCase(Locale.ROOT).endsWith("@" + domain)
                            || sender.toLowerCase(Locale.ROOT).endsWith("." + domain);
                }
                case FILENAME -> filename.toLowerCase(Locale.ROOT).contains(pattern.toLowerCase(Locale.ROOT));
            };
        }
        catch (PatternSyntaxException ex) {
            // A broken pattern is a configuration error, not a document error: report it and carry on
            // rather than failing the whole document.
            log.warn("The classification rule {} has an invalid regular expression: {}", rule.getId(),
                    ex.getMessage());
            return false;
        }
    }

    /** A matched rule together with what it matched, kept for the rationale. */
    record Match(DocumentTypeRule rule) {
    }
}
