package de.buerokratiecopilot.radar;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import de.buerokratiecopilot.radar.RadarFindingService.Draft;

/**
 * One way of noticing a possible problem (spec 6.4).
 *
 * <p>A rule is deterministic: the same data gives the same finding with the same severity. The severity is part of what a
 * rule decides; a language model may explain a finding afterwards but never rates it. Every rule describes itself, so that
 * the Radar can show a person why a finding exists and what closes it.</p>
 */
public interface RadarRule {

    /** What a rule needs, when it fires, what it says and when it is done. All texts are shown to people. */
    record Description(String id, int version, String findingType, String requiredData, String trigger,
                       String severityRule, String explanation, List<String> allowedActions, String closedWhen,
                       String reopenedWhen) {
        public boolean isComplete() {
            return notBlank(id) && version > 0 && notBlank(findingType) && notBlank(requiredData) && notBlank(trigger)
                    && notBlank(severityRule) && notBlank(explanation) && allowedActions != null && !allowedActions.isEmpty()
                    && notBlank(closedWhen) && notBlank(reopenedWhen);
        }

        private static boolean notBlank(String text) {
            return text != null && !text.isBlank();
        }
    }

    Description describe();

    /** Findings about one document that was just processed. */
    default List<Draft> onDocument(UUID tenantId, UUID documentId) {
        return List.of();
    }

    /** Findings from looking at the whole tenant; run on a schedule, because time passes without any event. */
    default List<Draft> onSweep(UUID tenantId) {
        return List.of();
    }

    /**
     * Whether {@link #onSweep} names every situation that currently exists. If so, an open finding of this rule that the
     * sweep no longer names is resolved by the platform: the situation is gone.
     */
    default boolean sweepIsComplete() {
        return false;
    }

    /** A stable fingerprint of the data a finding rests on (see {@code radar_findings.material_hash}). */
    static String fingerprint(Object... parts) {
        StringBuilder text = new StringBuilder();
        for (Object part : parts) {
            text.append(part).append('\u001f');
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is part of every Java platform", ex);
        }
    }
}
