package de.buerokratiecopilot.documents.dates;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What a date in a German letter actually is.
 *
 * <p>This is the algorithm the feature rests on, and it is tested without a database, a Spring context or a
 * document: the decisions are pure, and they are the part that can be wrong in a way a person would notice —
 * a payment deadline filed as a letter date, or the letter's own date put in the calendar as an appointment.</p>
 *
 * <p>The cases below are sentences a real letter contains, and the expected answer is what the words say, not
 * what any law says. The classifier never claims a legal deadline; it reports that the letter named one.</p>
 */
class DateClassifierTest {

    /** Classifies the first date-looking token in a sentence, the way the service does. */
    private static DateClassifier.Classified classify(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\b\\d{1,2}[.\\-/]\\d{1,2}[.\\-/]\\d{2,4}\\b")
                .matcher(text);
        assertThat(m.find()).as("the test sentence must contain a date: %s", text).isTrue();
        return DateClassifier.classify(text, m.start(), m.end() - m.start());
    }

    @Nested
    @DisplayName("an administrative deadline is a deadline")
    class AdministrativeDeadline {

        @Test
        @DisplayName("\"bis spätestens\" marks a deadline, not an unknown date")
        void spaetestensIsADeadline() {
            // The most ordinary phrasing in German official mail, and it matched no rule at all: a photographed
            // request from an authority arrived with the date filed as OTHER, which is kept but never calendared.
            String text = "Wir bitten um Übersendung bis spätestens 03.12.2026.";
            int at = text.indexOf("03.12.2026");

            DateClassifier.Classified classified = DateClassifier.classify(text, at, "03.12.2026".length());

            assertThat(classified.kind()).isEqualTo(DateClassifier.Kind.DEADLINE);
            assertThat(DateClassifier.createAutomatically(classified)).isTrue();
        }

        @Test
        @DisplayName("\"Übersendung\" and \"einzureichen\" also name a deadline")
        void handingInPapersIsADeadline() {
            String text = "Die Unterlagen sind bis 15.01.2027 einzureichen.";
            int at = text.indexOf("15.01.2027");

            assertThat(DateClassifier.classify(text, at, "15.01.2027".length()).kind())
                    .isEqualTo(DateClassifier.Kind.DEADLINE);
        }

        @Test
        @DisplayName("a payment keeps its own kind, although it also says \"bis zum\"")
        void paymentStaysAPayment() {
            // The generic deadline markers must not swallow this: "Zahlung" is the better description, and the
            // nearest marker decides.
            String text = "Zahlung bis zum 31.10.2026 auf das bekannte Konto.";
            int at = text.indexOf("31.10.2026");

            assertThat(DateClassifier.classify(text, at, "31.10.2026".length()).kind())
                    .isEqualTo(DateClassifier.Kind.PAYMENT_DEADLINE);
        }

        @Test
        @DisplayName("a written-out appointment is still an appointment")
        void writtenOutAppointment() {
            String text = "Das Gespräch findet am 18. November 2026 um 14:30 Uhr statt.";
            int at = text.indexOf("18. November 2026");

            DateClassifier.Classified classified =
                    DateClassifier.classify(text, at, "18. November 2026".length());

            assertThat(classified.kind()).isEqualTo(DateClassifier.Kind.APPOINTMENT);
            assertThat(classified.evidence()).containsIgnoringCase("gespräch");
        }
    }

    @Nested
    @DisplayName("dates that name an obligation go to the calendar")
    class Obligations {

        @Test
        @DisplayName("a payment date is a payment deadline")
        void payment() {
            // The case from real use: a photographed letter saying when to pay.
            assertThat(classify("Bitte überweisen Sie den Betrag bis zum 31.10.2026 auf unser Konto.").kind())
                    .isEqualTo(DateClassifier.Kind.PAYMENT_DEADLINE);
            assertThat(classify("Die Zahlung ist fällig am 15.11.2026.").kind())
                    .isEqualTo(DateClassifier.Kind.PAYMENT_DEADLINE);
        }

        @Test
        @DisplayName("a reply date is an objection deadline")
        void objection() {
            assertThat(classify("Der Widerspruch muss bis zum 12.09.2026 eingelegt werden.").kind())
                    .isEqualTo(DateClassifier.Kind.OBJECTION_DEADLINE);
            assertThat(classify("Wir bitten um Stellungnahme bis 03.03.2026.").kind())
                    .isEqualTo(DateClassifier.Kind.OBJECTION_DEADLINE);
        }

        @Test
        @DisplayName("a date with a time is an appointment")
        void appointment() {
            assertThat(classify("Ihr Termin am 12.11.2026 um 10:00 Uhr bei der Behörde.").kind())
                    .isEqualTo(DateClassifier.Kind.APPOINTMENT);
            assertThat(classify("Hiermit laden wir Sie zum Gespräch am 05.05.2026 ein.").kind())
                    .isEqualTo(DateClassifier.Kind.APPOINTMENT);
        }

        @Test
        @DisplayName("an expiry date ends something")
        void validity() {
            assertThat(classify("Ihr Ausweis ist gültig bis 30.06.2027.").kind())
                    .isEqualTo(DateClassifier.Kind.VALIDITY_END);
        }

        @Test
        @DisplayName("those four are acted on without asking")
        void actedOn() {
            for (DateClassifier.Kind kind : new DateClassifier.Kind[] {
                    DateClassifier.Kind.PAYMENT_DEADLINE, DateClassifier.Kind.OBJECTION_DEADLINE,
                    DateClassifier.Kind.APPOINTMENT, DateClassifier.Kind.VALIDITY_END}) {
                assertThat(kind.belongsInCalendar()).as(kind.name()).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("dates that are not obligations stay out of the calendar")
    class NotObligations {

        @Test
        @DisplayName("the date the letter was written is not an appointment")
        void documentDate() {
            // This is the one that would poison a calendar if every date were added: a letter's own date.
            DateClassifier.Classified classified =
                    classify("Berlin, den 01.03.2026. Sehr geehrte Damen und Herren, ...");
            // OTHER is the answer for "a date with no context that says what it is", and it is what keeps
            // this date out of the calendar.
            assertThat(classified.kind()).isEqualTo(DateClassifier.Kind.OTHER);
            assertThat(DateClassifier.createAutomatically(classified)).isFalse();
        }

        @Test
        @DisplayName("a letter date is recognised as such and kept out")
        void documentDateIsKept() {
            DateClassifier.Classified classified = classify("Datum 01.03.2026. Betreff: Ihre Anfrage");
            assertThat(classified.kind()).isEqualTo(DateClassifier.Kind.DOCUMENT_DATE);
            assertThat(classified.inCalendar()).isFalse();
            assertThat(DateClassifier.createAutomatically(classified)).isFalse();
        }

        @Test
        @DisplayName("a date with no recognisable context is left alone")
        void unknown() {
            DateClassifier.Classified classified = classify("Aktenzeichen 4711, Vorgang 22.07.2026");
            assertThat(classified.inCalendar()).isFalse();
            assertThat(DateClassifier.createAutomatically(classified)).isFalse();
        }
    }

    @Nested
    @DisplayName("the nearest marker decides - the defect the audit found")
    class ProximityDecides {

        @Test
        @DisplayName("a Mahnung's own date is a letter date, not a payment deadline")
        void aMahnungDoesNotTurnItsDateIntoADeadline() {
            // Before the fix this returned PAYMENT_DEADLINE with 0.9, because the word "Mahnung" matched the
            // payment rule first even though "Datum:" stood right beside the date and "Mahnung" ninety
            // characters away - and the letter's own date went into the calendar as a deadline.
            DateClassifier.Classified classified = classify(
                    "Mahnung. Sehr geehrte Damen und Herren, wir erinnern an Ihre Zahlung. "
                            + "Datum: 12.10.2026. Aktenzeichen 4711.");
            assertThat(classified.kind()).isEqualTo(DateClassifier.Kind.DOCUMENT_DATE);
            assertThat(DateClassifier.createAutomatically(classified)).isFalse();
        }

        @Test
        @DisplayName("two dates in one sentence get their own markers")
        void twoDatesGetTheirOwnMarkers() {
            // Before the fix both dates came out as PAYMENT_DEADLINE 0.9, because the payment rule was checked
            // before the appointment rule and the window held both.
            String text = "Ihr Termin am 12.11.2026, die Zahlung bis 30.11.2026.";
            int first = text.indexOf("12.11.2026");
            int second = text.indexOf("30.11.2026");

            DateClassifier.Classified appointment =
                    DateClassifier.classify(text, first, "12.11.2026".length());
            DateClassifier.Classified payment =
                    DateClassifier.classify(text, second, "30.11.2026".length());

            assertThat(appointment.kind()).isEqualTo(DateClassifier.Kind.APPOINTMENT);
            assertThat(payment.kind()).isEqualTo(DateClassifier.Kind.PAYMENT_DEADLINE);
        }
    }

    @Nested
    @DisplayName("the wording nearest the date decides")
    class NearestWins {

        @Test
        @DisplayName("a payment sentence and an appointment sentence are not confused")
        void separate() {
            // Two dates in one letter: the window around each must pick its own marker, or one deadline
            // would be filed as the other.
            String text = "Die Zahlung ist bis zum 31.10.2026 fällig.";
            assertThat(classify(text).kind()).isEqualTo(DateClassifier.Kind.PAYMENT_DEADLINE);

            String appointment = "Der Termin findet am 12.11.2026 statt.";
            assertThat(classify(appointment).kind()).isEqualTo(DateClassifier.Kind.APPOINTMENT);
        }

        @Test
        @DisplayName("a marker far from the date is a weak reading and is not acted on")
        void weakReadingIsNotActedOn() {
            // The marker sits at the far edge of the window: the words may belong to another sentence, so the
            // date is classified but not calendared.
            String far = "Zahlung. " + " ".repeat(200) + "Vorgang vom 12.11.2026.";
            DateClassifier.Classified classified = classify(far);
            assertThat(DateClassifier.createAutomatically(classified))
                    .as("a marker that far from the date must not create an entry by itself")
                    .isFalse();
        }
    }
}
