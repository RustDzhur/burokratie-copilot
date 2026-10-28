package de.buerokratiecopilot.documents.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.documents.domain.DocumentTypeRule;

/**
 * Verifies how rule weights are combined (spec §9.3).
 *
 * <p>The property that matters for the automation threshold: several independent hints must agree
 * before a type is accepted, while one weak hint must never be enough. An average would let a single
 * strong keyword decide, and a sum would let a long document with many weak matches win.</p>
 */
class RuleBasedClassifierTest {

    private static DocumentTypeRule rule(String pattern, double weight) {
        return DocumentTypeRule.of(java.util.UUID.randomUUID(), null,
                DocumentTypeRule.RuleType.KEYWORD, pattern, weight);
    }

    @Test
    @DisplayName("two independent hints of 0.6 combine to 0.84, not to 0.6 and not to 1.2")
    void combinesWeightsWithProbabilisticOr() {
        double combined = RuleBasedClassifier.combine(List.of(
                new RuleBasedClassifier.Match(rule("rechnung", 0.6)),
                new RuleBasedClassifier.Match(rule("netto", 0.6))));

        assertThat(combined).isCloseTo(0.84, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    @DisplayName("one weak hint stays weak")
    void oneWeakHintStaysBelowTheThreshold() {
        double combined = RuleBasedClassifier.combine(List.of(
                new RuleBasedClassifier.Match(rule("netto", 0.3))));

        assertThat(combined).isLessThan(0.6);
    }

    @Test
    @DisplayName("many hints converge towards certainty without ever exceeding it")
    void manyHintsConvergeToOne() {
        double combined = RuleBasedClassifier.combine(List.of(
                new RuleBasedClassifier.Match(rule("rechnungsnummer", 0.9)),
                new RuleBasedClassifier.Match(rule("umsatzsteuer", 0.9)),
                new RuleBasedClassifier.Match(rule("zahlbar bis", 0.9))));

        assertThat(combined).isGreaterThan(0.99).isLessThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName("a single strong rule can reach the acceptance threshold on its own")
    void oneStrongRuleReachesTheThreshold() {
        double combined = RuleBasedClassifier.combine(List.of(
                new RuleBasedClassifier.Match(rule("letzte mahnung", 0.95))));

        assertThat(combined).isGreaterThanOrEqualTo(0.85);
    }

    @Test
    @DisplayName("a rule with an impossible weight is rejected at creation")
    void invalidRulesAreRejected() {
        // Guarding this in the domain (and not only in the API layer) is what keeps a configuration
        // mistake from silently skewing every score.
        assertThatThrownBy(() -> DocumentTypeRule.of(java.util.UUID.randomUUID(), null,
                DocumentTypeRule.RuleType.KEYWORD, "rechnung", 1.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DocumentTypeRule.of(java.util.UUID.randomUUID(), null,
                DocumentTypeRule.RuleType.KEYWORD, "x", 0.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("umlauts, ß, accents, hyphens and case are levelled, so OCR without dots and typed ASCII meet the same rule")
    void foldsForComparison() {
        assertThat(RuleBasedClassifier.fold("Kündigung")).isEqualTo(RuleBasedClassifier.fold("KUENDIGUNG")).isEqualTo("kuendigung");
        assertThat(RuleBasedClassifier.fold("Straße")).isEqualTo("strasse");
        assertThat(RuleBasedClassifier.fold("Gewerbe-Abmeldung")).isEqualTo("gewerbe abmeldung");
        assertThat(RuleBasedClassifier.fold("  Café \n\t Crème ")).isEqualTo("cafe creme");
    }

    @Test
    @DisplayName("Cyrillic stays Cyrillic: the breve of 'й' is part of the letter, not an accent")
    void keepsCyrillic() {
        assertThat(RuleBasedClassifier.fold("Заключение МЕДИЦИНСКОЙ комиссии")).isEqualTo("заключение медицинской комиссии");
    }
}
