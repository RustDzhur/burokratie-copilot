package de.buerokratiecopilot.documents.dates;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What a date in a German letter actually is, decided from the words around it (§13.2).
 *
 * <p><strong>What this does and does not claim.</strong> It reads the <em>wording</em>, not the law. "Zahlung
 * bis zum 31.10.2026" is a payment date because the sentence says so, and saying so makes no claim about any
 * legal rule. The platform still does not compute deadlines from law — its register of rules ships empty on
 * purpose — but a date that the letter itself names as a deadline needs no rule, only reading.</p>
 *
 * <p><strong>Why classification is the whole point.</strong> A letter contains many dates, and only some of
 * them belong in a calendar. The date of the letter itself is not an appointment; a reference date is not a
 * deadline. Putting every date in the calendar turns it into something people stop reading, and leaving the
 * decision to a person means a button and a click for every letter — which is the interface clutter this
 * removes. So the classifier sorts them and only the ones that name an obligation or an appointment become
 * entries.</p>
 *
 * <p><strong>Order matters.</strong> A sentence can contain several markers ("Termin am 12.11.2026, Zahlung
 * bis 30.11.2026" is two dates), and the words nearest the date are the ones that describe it. The rules
 * therefore look at a window around the date, and the most specific obligation wins over a generic word like
 * "Datum".</p>
 *
 * <p><strong>Unknown is a real answer.</strong> A date with no recognisable context is {@link Kind#OTHER} and
 * goes nowhere. Guessing would put the wrong deadline in a calendar, and a wrong deadline is worse than a
 * missing one: it is relied upon.</p>
 */
public final class DateClassifier {

    /** What a date in a document is, as far as the wording says. */
    public enum Kind {
        /** "Zahlung bis", "fällig am", "Zahlungsfrist" — money is owed by that date. */
        PAYMENT_DEADLINE,
        /** "Widerspruch", "Einspruch", "Stellungnahme bis" — a reply is expected by that date. */
        OBJECTION_DEADLINE,
        /** "Termin", "um ... Uhr", "Einladung" — somebody is expected somewhere. */
        APPOINTMENT,
        /** "gültig bis", "läuft ab", "Ablauf" — something ends then. */
        VALIDITY_END,
        /** "Datum", "vom", "Schreiben vom" — when the letter was written. Not an appointment. */
        DOCUMENT_DATE,
        /** Recognised in the text but not in any of the above: shown, never calendared. */
        OTHER;

        /** Whether this kind belongs in a calendar. A letter's own date does not. */
        public boolean belongsInCalendar() {
            return this == PAYMENT_DEADLINE || this == OBJECTION_DEADLINE || this == APPOINTMENT
                    || this == VALIDITY_END;
        }
    }

    /** The result: what it is, how sure that is, and the words that decided it. */
    public record Classified(Kind kind, double confidence, String evidence) {

        boolean inCalendar() {
            return kind.belongsInCalendar();
        }
    }

    /**
     * How far around the date the deciding words may stand, in characters.
     *
     * <p>A German sentence puts the date after its marker ("Zahlung bis zum 31.10.2026") or before its
     * explainer ("Der Termin am 12.11.2026 findet statt"), so the window has to cover both sides. Ninety
     * characters is roughly one clause — enough to catch the marker, short enough not to catch the next
     * sentence's marker instead.</p>
     */
    private static final int WINDOW = 90;

    /** Markers, most specific first. The first rule that matches the window decides. */
    private record Rule(Kind kind, Pattern pattern) {
    }

    private static final List<Rule> RULES = List.of(
            // Money owed. Checked before the generic "Frist" so that "Zahlungsfrist" is a payment, not a
            // reply.
            new Rule(Kind.PAYMENT_DEADLINE, Pattern.compile(
                    "zahlung(?:sfrist|stermin)?|zu zahlen|zahlen sie|überweis(?:en|ung)|fällig|"
                            + "rechnung(?:sbetrag)?\\s+(?:bis|zum)|lastschrift|mahnung",
                    Pattern.CASE_INSENSITIVE)),
            // A reply is expected. "Widerspruch" and "Einspruch" are the two German legal replies whose
            // names appear on the letter itself.
            new Rule(Kind.OBJECTION_DEADLINE, Pattern.compile(
                    "widerspruch|einspruch|stellungnahme|antwort(?:en)?\\s+(?:bis|zum)|"
                            + "erwiderung|rückmeldung|äußerung",
                    Pattern.CASE_INSENSITIVE)),
            // Somebody is expected somewhere. "Uhr" catches an appointment whose time is written out without
            // the word "Termin".
            new Rule(Kind.APPOINTMENT, Pattern.compile(
                    "termin|einladung|laden\\s+wir\\s+sie|besprechung|gespräch|beratung|"
                            + "vorsprache|vorladung|\\buhr\\b",
                    Pattern.CASE_INSENSITIVE)),
            // Something ends.
            new Rule(Kind.VALIDITY_END, Pattern.compile(
                    "gültig\\s+bis|läuft\\s+ab|ablauf|befristet\\s+bis|bis\\s+einschließlich|"
                            + "gültigkeit",
                    Pattern.CASE_INSENSITIVE)),
            // When the letter was written. Checked last, because "Datum" and "vom" appear in almost every
            // letter and would otherwise swallow the other four.
            new Rule(Kind.DOCUMENT_DATE, Pattern.compile(
                    "datum|schreiben\\s+vom|ausgestellt|briefkopf|\\bvom\\b",
                    Pattern.CASE_INSENSITIVE)));

    private DateClassifier() {
    }

    /**
     * Classifies the date that appears at {@code dateAt} in {@code text}.
     *
     * <p>Looks only at a window around the date, because the words that describe a date are the ones beside
     * it — a letter that mentions a payment in one paragraph and an appointment in the next must not colour
     * one date with the other's marker.</p>
     */
    public static Classified classify(String text, int dateAt, int dateLength) {
        if (text == null || text.isEmpty() || dateAt < 0) {
            return new Classified(Kind.OTHER, 0, null);
        }
        int from = Math.max(0, dateAt - WINDOW);
        int to = Math.min(text.length(), dateAt + dateLength + WINDOW);
        String window = text.substring(from, to).toLowerCase(Locale.GERMAN);

        // The marker NEAREST the date decides, not the first rule in the list that matches.
        //
        // Checking in list order was a real defect: "Mahnung / Datum: 12.10.2026" put the letter's own date in
        // the calendar as a payment deadline, because "Mahnung" matched the payment rule first even though
        // "Datum:" stood right beside the date and "Mahnung" stood ninety characters away. The same defect
        // marked both dates of "Termin am 12.11.2026, Zahlung bis 30.11.2026" as payment deadlines.
        //
        // Distance alone is the right measure: in a German sentence the words that describe a date are the
        // ones next to it, and a marker further away belongs to another clause or another sentence.
        Rule bestRule = null;
        String bestEvidence = null;
        int bestDistance = Integer.MAX_VALUE;
        int dateStart = dateAt - from;
        for (Rule rule : RULES) {
            var matcher = rule.pattern().matcher(window);
            while (matcher.find()) {
                int distance = Math.abs(matcher.start() - dateStart);
                // Ties go to the earlier rule only when the distances are equal, which keeps the outcome
                // stable instead of depending on which pattern happened to be compiled first.
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestRule = rule;
                    bestEvidence = matcher.group();
                }
            }
        }
        if (bestRule == null) {
            return new Classified(Kind.OTHER, 0, null);
        }
        // Confidence is high when the marker stands right beside the date, and lower when it is at the edge
        // of the window - the caller uses this to decide whether to act on it.
        double confidence = bestDistance <= 30 ? 0.9 : bestDistance <= 60 ? 0.7 : 0.5;
        return new Classified(bestRule.kind(), confidence, bestEvidence == null ? null : bestEvidence.trim());
    }

    /**
     * Whether a date of this kind should become a calendar entry without asking anybody.
     *
     * <p>Only when the wording names an obligation or an appointment <em>and</em> the marker stands close to
     * the date. A weak reading is left alone: an automatic entry that is wrong is relied upon, which is worse
     * than one that had to wait.</p>
     */
    public static boolean createAutomatically(Classified classified) {
        return classified.inCalendar() && classified.confidence() >= 0.7;
    }
}
