package de.buerokratiecopilot.documents.dates;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The algorithm with a vocabulary handed in: nearest word, confidence by distance, and the resolution of
 * overlapping words.
 *
 * <p><strong>Why this test hands in its own words.</strong> Production reads the vocabulary from
 * {@code date_markers} through {@link DateMarkerClassifier}; the algorithm itself is pure and is tested here
 * with a handful of examples. That keeps the test independent of a database while still being the same code
 * the production path runs — {@link DateClassifier#classify(String, int, int, List)} is the single
 * implementation behind both.</p>
 */
class DateMarkerClassifierTest {

    private static final List<DateClassifier.Marker> VOCABULARY = List.of(
            new DateClassifier.Marker(DateClassifier.Kind.PAYMENT_DEADLINE, "zahlung"),
            new DateClassifier.Marker(DateClassifier.Kind.OBJECTION_DEADLINE, "widerspruch"),
            new DateClassifier.Marker(DateClassifier.Kind.APPOINTMENT, "termin"));

    /** Classifies the first date-shaped token, the way the service does. */
    private static DateClassifier.Classified classify(String text, List<DateClassifier.Marker> markers) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\b\\d{1,2}[.\\-/]\\d{1,2}[.\\-/]\\d{2,4}\\b")
                .matcher(text);
        assertThat(m.find()).as("the test sentence must contain a date: %s", text).isTrue();
        return DateClassifier.classify(text, m.start(), m.end() - m.start(), markers);
    }

    private static DateClassifier.Classified classify(String text) {
        return classify(text, VOCABULARY);
    }

    @Nested
    @DisplayName("the word nearest the date decides")
    class NearestWins {

        @Test
        @DisplayName("a closer appointment wins over a payment word further away")
        void closerWordWins() {
            DateClassifier.Classified classified = classify("Zahlung und Termin am 12.11.2026");

            assertThat(classified.kind()).isEqualTo(DateClassifier.Kind.APPOINTMENT);
            assertThat(classified.evidence()).isEqualTo("termin");
        }

        @Test
        @DisplayName("the date's own explanation wins over the sentence's earlier topic")
        void nearerExplanationWins() {
            // "Widerspruch" is the topic, "Termin" stands beside the date: the date is an appointment, not a
            // reply deadline.
            DateClassifier.Classified classified = classify(
                    "Widerspruch. Der Termin am 12.11.2026 findet statt.");

            assertThat(classified.kind()).isEqualTo(DateClassifier.Kind.APPOINTMENT);
        }

        @Test
        @DisplayName("a word before and a word after the date are compared by distance, not by position")
        void wordAfterTheDateCountsToo() {
            DateClassifier.Classified classified = classify("Am 12.11.2026 ist die Zahlung fällig.");
            // "zahlung" stands after the date and is still its explanation.
            assertThat(classified.kind()).isEqualTo(DateClassifier.Kind.PAYMENT_DEADLINE);
        }
    }

    @Nested
    @DisplayName("overlapping words: the longer, more specific one wins")
    class OverlappingWords {

        @Test
        @DisplayName("\"Zahlungsfrist\" is a payment deadline although it contains \"frist\"")
        void paymentFristStaysAPayment() {
            // Substring matching (the table's documented contract) finds "frist" inside "Zahlungsfrist", and
            // the shorter word starts closer to the date. It must not turn the payment into an
            // administrative deadline.
            List<DateClassifier.Marker> markers = List.of(
                    new DateClassifier.Marker(DateClassifier.Kind.DEADLINE, "frist"),
                    new DateClassifier.Marker(DateClassifier.Kind.PAYMENT_DEADLINE, "zahlung"),
                    new DateClassifier.Marker(DateClassifier.Kind.PAYMENT_DEADLINE, "zahlungsfrist"));

            DateClassifier.Classified classified = classify("Zahlungsfrist: 31.10.2026", markers);

            assertThat(classified.kind()).isEqualTo(DateClassifier.Kind.PAYMENT_DEADLINE);
            assertThat(classified.evidence()).isEqualTo("zahlungsfrist");
        }

        @Test
        @DisplayName("\"Widerspruchsfrist\" stays an objection deadline")
        void objectionFristStaysAnObjection() {
            List<DateClassifier.Marker> markers = List.of(
                    new DateClassifier.Marker(DateClassifier.Kind.DEADLINE, "frist"),
                    new DateClassifier.Marker(DateClassifier.Kind.OBJECTION_DEADLINE, "widerspruch"),
                    new DateClassifier.Marker(DateClassifier.Kind.OBJECTION_DEADLINE, "widerspruchsfrist"));

            assertThat(classify("Widerspruchsfrist: 12.09.2026", markers).kind())
                    .isEqualTo(DateClassifier.Kind.OBJECTION_DEADLINE);
        }

        @Test
        @DisplayName("\"Zahlungstermin\" is a payment date, not an appointment")
        void paymentTerminStaysAPayment() {
            List<DateClassifier.Marker> markers = List.of(
                    new DateClassifier.Marker(DateClassifier.Kind.APPOINTMENT, "termin"),
                    new DateClassifier.Marker(DateClassifier.Kind.PAYMENT_DEADLINE, "zahlung"),
                    new DateClassifier.Marker(DateClassifier.Kind.PAYMENT_DEADLINE, "zahlungstermin"));

            assertThat(classify("Zahlungstermin: 31.10.2026", markers).kind())
                    .isEqualTo(DateClassifier.Kind.PAYMENT_DEADLINE);
        }
    }

    @Nested
    @DisplayName("confidence follows the distance")
    class Confidence {

        @Test
        @DisplayName("<= 30 characters: 0.9 and acted on")
        void closeIsConfident() {
            DateClassifier.Classified classified = classify("Zahlung" + " ".repeat(23) + "31.10.2026");
            // The word starts exactly 30 characters before the date.
            assertThat(classified.confidence()).isEqualTo(0.9);
            assertThat(DateClassifier.createAutomatically(classified)).isTrue();
        }

        @Test
        @DisplayName("30..60 characters: 0.7 and still acted on")
        void middleIsUncertain() {
            DateClassifier.Classified classified = classify("Zahlung" + " ".repeat(53) + "31.10.2026");
            // The word starts exactly 60 characters before the date, the last distance that is still 0.7.
            assertThat(classified.confidence()).isEqualTo(0.7);
            assertThat(DateClassifier.createAutomatically(classified)).as("0.7 is the threshold").isTrue();
        }

        @Test
        @DisplayName("> 60 characters: 0.5 and NOT put in the calendar")
        void farIsWeak() {
            DateClassifier.Classified classified = classify("Zahlung" + " ".repeat(70) + "31.10.2026");

            assertThat(classified.confidence()).isEqualTo(0.5);
            assertThat(classified.kind()).isEqualTo(DateClassifier.Kind.PAYMENT_DEADLINE);
            assertThat(DateClassifier.createAutomatically(classified))
                    .as("a weak reading may belong to another sentence and must not create an entry")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("a date without a known word is not guessed")
    class NoWord {

        @Test
        @DisplayName("no marker in the window means OTHER with no confidence")
        void unknown() {
            DateClassifier.Classified classified = classify("Aktenzeichen 4711, Vorgang 22.07.2026");

            assertThat(classified.kind()).isEqualTo(DateClassifier.Kind.OTHER);
            assertThat(classified.confidence()).isZero();
            assertThat(classified.evidence()).isNull();
            assertThat(DateClassifier.createAutomatically(classified)).isFalse();
        }

        @Test
        @DisplayName("an empty vocabulary is not an error")
        void emptyVocabulary() {
            DateClassifier.Classified classified = classify("Zahlung bis 31.10.2026", List.of());
            assertThat(classified.kind()).isEqualTo(DateClassifier.Kind.OTHER);
        }
    }
}
