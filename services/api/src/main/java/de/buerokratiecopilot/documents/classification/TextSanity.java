package de.buerokratiecopilot.documents.classification;

import java.util.Locale;
import java.util.Set;

/**
 * A cheap check whether text read from a picture is text at all. A page read upside down or from a bad photo comes out as
 * letters without words ("ee v Sn en Arie pun yaqsua"), and rules working on it find nothing or find the wrong thing.
 *
 * <p>Ordinary German and English prose is full of small function words. A text of some length that has almost none of them
 * was not read properly. The check says "not readable"; it never says what the text means.</p>
 */
public final class TextSanity {

    private static final Set<String> FUNCTION_WORDS = Set.of(
            "der", "die", "das", "und", "ist", "nicht", "sie", "ich", "zu", "mit", "für", "von", "den", "ein", "eine", "auf", "im",
            "bei", "am", "an", "wird", "werden", "bitte", "wir", "dem", "des", "als", "auch", "oder", "sind", "nach", "aus", "um",
            "the", "and", "of", "to", "is", "for", "with", "you", "your");

    /** Below this many words there is too little text to judge; it is then neither trusted nor distrusted. */
    private static final int MIN_WORDS = 25;
    private static final double MIN_SHARE = 0.04;

    private TextSanity() {
    }

    /** Whether the text is empty or looks like noise rather than words. */
    public static boolean looksUnreadable(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String[] words = text.toLowerCase(Locale.ROOT).split("[^\\p{L}]+");
        int total = 0;
        int known = 0;
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            total++;
            if (FUNCTION_WORDS.contains(word)) {
                known++;
            }
        }
        if (total < MIN_WORDS) {
            return total == 0;
        }
        return (double) known / total < MIN_SHARE;
    }
}
