package de.buerokratiecopilot.documents.zordnung;

import java.util.Optional;
import java.util.UUID;

/**
 * Whether the platform may assign a voucher to a client without asking (§5.2).
 *
 * <p>The specification's rule, stated as code: a document is assigned automatically only above a confidence
 * threshold, and below it the voucher goes to the queue as "Ohne Mandant" with a highlight. The reason is not
 * caution for its own sake - a wrong assignment sends one client's paperwork into another client's bookkeeping,
 * and nobody notices until an audit.</p>
 *
 * <p>Two consequences follow and both are deliberate. There is no "best guess" fallback: when two clients match
 * equally well the answer is nobody, not the first one alphabetically. And the threshold is a named constant, not
 * a scattered {@code 0.9}, so the number the pilot agrees on is in one place.</p>
 */
public final class MandantRouting {

    /** The starting threshold from the specification (§12.5); to be fixed with the first office. */
    public static final double CONFIDENCE_THRESHOLD = 0.9;

    private MandantRouting() {
    }

    /**
     * The client to assign without asking, or empty when a person has to decide.
     *
     * @param candidate the best match, or null when nothing matched
     * @param confidence how sure the matcher is, from 0 to 1
     */
    public static Optional<UUID> autoAssign(UUID candidate, double confidence) {
        if (candidate == null) {
            return Optional.empty();
        }
        if (Double.isNaN(confidence) || confidence < CONFIDENCE_THRESHOLD) {
            return Optional.empty();
        }
        return Optional.of(candidate);
    }

    /**
     * True when two matches are close enough that choosing one would be a coin toss.
     *
     * <p>Returns true for a missing second confidence as well: a matcher that cannot say how sure it is about the
     * runner-up cannot say that the first one is clearly better, and the answer must then be "ask a person".</p>
     */
    public static boolean isAmbiguous(double best, Double second) {
        if (second == null || Double.isNaN(second)) {
            return true;
        }
        // Within a tenth of each other there is no defensible winner: "94 % against 90 %" is not a decision, it
        // is the same evidence pointing at two clients.
        return best - second < 0.1;
    }
}
