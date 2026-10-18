package de.buerokratiecopilot.documents.dates;

import java.util.List;
import java.util.Locale;

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
 * <p><strong>The vocabulary is data, not code.</strong> The words that explain a date used to be regular
 * expressions compiled into this class; a word that was not in them did not exist, and a user could not add
 * one. Since migration V69 they live in {@code date_markers}: the platform's defaults
 * ({@code tenant_id IS NULL}) plus words a tenant added or switched off. The production path
 * ({@link DateMarkerClassifier}) reads them through {@link DateMarkerService}; this class only holds the
 * algorithm — and a small built-in list for unit tests, which is deliberately a subset of the defaults.</p>
 *
 * <p><strong>Order matters.</strong> A sentence can contain several markers ("Termin am 12.11.2026, Zahlung
 * bis 30.11.2026" is two dates), and the words nearest the date are the ones that describe it. The rules
 * therefore look at a window around the date, and the nearest word wins.</p>
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
        /**
         * "bis spätestens 03.12.2026", "Frist zur Vorlage", "Übersendung bis" — an administrative deadline:
         * the office expects the papers by then.
         *
         * <p>A distinct kind from {@link #OBJECTION_DEADLINE} because it is not a reply the law grants a
         * period for, and distinct from {@link #PAYMENT_DEADLINE} because no money is owed. Before this kind
         * existed, the most ordinary German phrasing — "Wir bitten um Übersendung bis spätestens 03.12.2026"
         * — matched no rule at all and the date was filed as OTHER, which is kept but never calendared: a
         * photographed request from an authority arrived with no deadline on it.</p>
         */
        DEADLINE,
        /** "Datum", "vom", "Schreiben vom" — when the letter was written. Not an appointment. */
        DOCUMENT_DATE,
        /** Recognised in the text but not in any of the above: shown, never calendared. */
        OTHER;

        /** Whether this kind belongs in a calendar. A letter's own date does not. */
        public boolean belongsInCalendar() {
            return this == PAYMENT_DEADLINE || this == OBJECTION_DEADLINE || this == APPOINTMENT
                    || this == VALIDITY_END || this == DEADLINE;
        }
    }

    /** The result: what it is, how sure that is, and the words that decided it. */
    public record Classified(Kind kind, double confidence, String evidence) {

        boolean inCalendar() {
            return kind.belongsInCalendar();
        }
    }

    /**
     * One word from the vocabulary: the kind it names and the plain text it is written as.
     *
     * <p>{@code phrase} is plain text, never a regular expression (that is also why the table stores it as
     * text and why the matcher below only searches for it). It is normalised to lower case with single spaces
     * when the marker is created, so the search does not depend on how somebody typed it.</p>
     */
    record Marker(Kind kind, String phrase) {

        Marker {
            phrase = phrase == null ? "" : phrase.replace('\u00A0', ' ').strip()
                    .replaceAll("\\s+", " ").toLowerCase(Locale.GERMAN);
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

    /**
     * The built-in vocabulary, <strong>only for unit tests that run without a database</strong>.
     *
     * <p>Production never uses this list: {@link DateMarkerClassifier} reads the tenant's effective
     * vocabulary from {@code date_markers}. This list exists so the pure algorithm can be tested without a
     * Spring context — exactly like the old classifier was tested. It must therefore stay a
     * <strong>subset</strong> of the defaults in {@code V70__date_markers_defaults.sql}: every entry here has
     * to exist there with the same kind, or a test would prove behaviour the product does not have. It is not
     * a second vocabulary and must not grow into one.</p>
     */
    private static final List<Marker> BUILT_IN_MARKERS = List.of(
            // Money owed.
            new Marker(Kind.PAYMENT_DEADLINE, "zahlung"),
            new Marker(Kind.PAYMENT_DEADLINE, "zahlen sie"),
            new Marker(Kind.PAYMENT_DEADLINE, "überweisen"),
            new Marker(Kind.PAYMENT_DEADLINE, "fällig"),
            new Marker(Kind.PAYMENT_DEADLINE, "mahnung"),
            // A reply is expected.
            new Marker(Kind.OBJECTION_DEADLINE, "widerspruch"),
            new Marker(Kind.OBJECTION_DEADLINE, "stellungnahme"),
            // Something has to be handed in.
            new Marker(Kind.DEADLINE, "spätestens"),
            new Marker(Kind.DEADLINE, "übersendung"),
            new Marker(Kind.DEADLINE, "einzureichen"),
            // Somebody is expected somewhere.
            new Marker(Kind.APPOINTMENT, "termin"),
            new Marker(Kind.APPOINTMENT, "gespräch"),
            new Marker(Kind.APPOINTMENT, "einladung"),
            new Marker(Kind.APPOINTMENT, "uhr"),
            // Something ends.
            new Marker(Kind.VALIDITY_END, "gültig bis"),
            // When the letter was written.
            new Marker(Kind.DOCUMENT_DATE, "datum"),
            new Marker(Kind.DOCUMENT_DATE, "vom"),
            new Marker(Kind.DOCUMENT_DATE, "schreiben vom"),
            new Marker(Kind.DOCUMENT_DATE, "ausgestellt"));

    private DateClassifier() {
    }

    /**
     * Classifies a date using the built-in test vocabulary.
     *
     * <p><strong>Tests only.</strong> A production call site must use {@link DateMarkerClassifier}, which
     * takes the words from the database so that a tenant's own word and a switched-off default actually
     * count. This overload exists because the algorithm is pure and worth testing without a database; it says
     * so in {@link #BUILT_IN_MARKERS} as well.</p>
     */
    public static Classified classify(String text, int dateAt, int dateLength) {
        return classify(text, dateAt, dateLength, BUILT_IN_MARKERS);
    }

    /**
     * Classifies the date that appears at {@code dateAt} in {@code text}, against the given vocabulary.
     *
     * <p>Looks only at a window around the date, because the words that describe a date are the ones beside
     * it — a letter that mentions a payment in one paragraph and an appointment in the next must not colour
     * one date with the other's marker.</p>
     *
     * <p><strong>Why phrases are matched as plain substrings.</strong> The vocabulary is edited by people in
     * the settings screen; a pattern a user accidentally types is either ineffective or a ReDoS trap. The
     * table's column comment therefore promises a case-insensitive substring match, and that is what happens
     * here. The price is that a short word can sit inside a longer one — "frist" inside "Zahlungsfrist",
     * "termin" inside "Zahlungstermin", "datum" inside "Ablaufdatum" — so overlapping words are resolved in
     * favour of the longer, more specific one below.</p>
     */
    static Classified classify(String text, int dateAt, int dateLength, List<Marker> markers) {
        if (text == null || text.isEmpty() || dateAt < 0 || dateAt > text.length() || markers == null
                || markers.isEmpty()) {
            return new Classified(Kind.OTHER, 0, null);
        }
        int length = Math.max(0, dateLength);
        int from = Math.max(0, dateAt - WINDOW);
        int to = Math.min(text.length(), dateAt + length + WINDOW);
        if (from >= to) {
            return new Classified(Kind.OTHER, 0, null);
        }
        String window = text.substring(from, to).toLowerCase(Locale.GERMAN);
        int dateStart = dateAt - from;

        // The marker NEAREST the date decides, not the first word in the list that matches.
        //
        // Checking in list order was a real defect: "Mahnung / Datum: 12.10.2026" put the letter's own date in
        // the calendar as a payment deadline, because "Mahnung" matched the payment rule first even though
        // "Datum:" stood right beside the date and "Mahnung" stood ninety characters away. The same defect
        // marked both dates of "Termin am 12.11.2026, Zahlung bis 30.11.2026" as payment deadlines.
        //
        // Distance alone is the right measure: in a German sentence the words that describe a date are the
        // ones next to it, and a marker further away belongs to another clause or another sentence.
        Marker bestMarker = null;
        String bestEvidence = null;
        int bestDistance = Integer.MAX_VALUE;
        int bestStart = -1;
        int bestEnd = -1;
        for (Marker marker : markers) {
            String phrase = marker.phrase();
            if (phrase.isEmpty()) {
                continue;
            }
            int start = startOfWord(window, phrase, 0);
            while (start >= 0) {
                int end = start + phrase.length();
                int distance = Math.abs(start - dateStart);
                if (better(start, end, distance, marker, bestStart, bestEnd, bestDistance, bestMarker)) {
                    bestMarker = marker;
                    bestDistance = distance;
                    bestStart = start;
                    bestEnd = end;
                    bestEvidence = window.substring(start, end);
                }
                start = startOfWord(window, phrase, end);
            }
        }
        if (bestMarker == null) {
            return new Classified(Kind.OTHER, 0, null);
        }
        // Confidence is high when the marker stands right beside the date, and lower when it is at the edge
        // of the window - the caller uses this to decide whether to act on it.
        double confidence = bestDistance <= 30 ? 0.9 : bestDistance <= 60 ? 0.7 : 0.5;
        return new Classified(bestMarker.kind(), confidence, bestEvidence == null ? null : bestEvidence.trim());
    }

    /**
     * The next place the phrase starts a word, or -1.
     *
     * <p><strong>Why the boundary matters.</strong> The vocabulary is a list of words, and a plain substring
     * search finds them inside other words: {@code rate} inside "Berater", {@code termin} inside "Terminal",
     * {@code uhr} inside "Uhrzeit". A letter that mentions its "Berater" next to a date would then be filed as
     * a payment deadline - exactly the kind of confident-looking wrong answer the review exists to prevent.</p>
     *
     * <p>The boundary is required only at the <em>start</em> of a word, not at the end. German builds its
     * nouns by compounding, so a vocabulary word is often the beginning of a longer word on the page:
     * "zahlung" has to match inside "Zahlungsfrist", "termin" inside "Terminbestätigung", "frist" inside
     * "Fristsetzung". Requiring an end boundary would lose all of those and leave the vocabulary matching only
     * the exact forms somebody happened to write down.</p>
     */
    private static int startOfWord(String window, String phrase, int fromIndex) {
        int start = window.indexOf(phrase, fromIndex);
        while (start > 0 && Character.isLetterOrDigit(window.charAt(start - 1))) {
            start = window.indexOf(phrase, start + 1);
        }
        return start;
    }

    /**
     * Whether a freshly found word should displace the current best.
     *
     * <p><strong>Overlap beats distance.</strong> Two vocabulary words can describe the same piece of text:
     * "Zahlungsfrist" contains "frist", "Zahlungstermin" contains "termin", "Ablaufdatum" contains "datum".
     * Taken by distance alone, the shorter word inside would win as soon as it starts nearer the date and
     * would turn a payment deadline into an administrative one — exactly the kind of mix-up this class exists
     * to prevent. When the two matches overlap, the longer one is the more specific description and wins.</p>
     *
     * <p>Otherwise the nearest word wins; at equal distance the longer phrase wins for the same reason, and
     * only then is the kind's declaration order the tie-breaker, so the result is stable instead of depending
     * on which word happened to be read first.</p>
     */
    private static boolean better(int start, int end, int distance, Marker candidate,
                                  int bestStart, int bestEnd, int bestDistance, Marker best) {
        if (best == null) {
            return true;
        }
        boolean overlapping = start < bestEnd && bestStart < end;
        int candidateLength = end - start;
        int bestLength = bestEnd - bestStart;
        if (overlapping && candidateLength != bestLength) {
            return candidateLength > bestLength;
        }
        if (distance != bestDistance) {
            return distance < bestDistance;
        }
        if (candidateLength != bestLength) {
            return candidateLength > bestLength;
        }
        return candidate.kind().ordinal() < best.kind().ordinal();
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
