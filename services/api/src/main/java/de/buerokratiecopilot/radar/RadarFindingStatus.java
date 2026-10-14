package de.buerokratiecopilot.radar;

import java.util.EnumSet;
import java.util.Set;

/**
 * The life of a finding. The backend alone decides which move is allowed (spec 6.3); the interface only offers the moves
 * this table allows.
 *
 * <pre>
 * NEW, UNDER_REVIEW, REOPENED -> any open decision: UNDER_REVIEW, CONFIRMED, DISMISSED, SNOOZED, RESOLVED
 * CONFIRMED                    -> UNDER_REVIEW, SNOOZED, RESOLVED
 * SNOOZED                      -> UNDER_REVIEW, DISMISSED, RESOLVED, REOPENED (the time is up, or new data arrived)
 * DISMISSED, RESOLVED          -> REOPENED (only new significant data, or a person who changes their mind)
 * </pre>
 */
public enum RadarFindingStatus {
    NEW, UNDER_REVIEW, CONFIRMED, DISMISSED, SNOOZED, RESOLVED, REOPENED;

    /** The moves from this status. */
    public Set<RadarFindingStatus> next() {
        return switch (this) {
            case NEW, UNDER_REVIEW, REOPENED -> EnumSet.of(UNDER_REVIEW, CONFIRMED, DISMISSED, SNOOZED, RESOLVED);
            case CONFIRMED -> EnumSet.of(UNDER_REVIEW, SNOOZED, RESOLVED);
            case SNOOZED -> EnumSet.of(UNDER_REVIEW, DISMISSED, RESOLVED, REOPENED);
            case DISMISSED, RESOLVED -> EnumSet.of(REOPENED);
        };
    }

    public boolean canMoveTo(RadarFindingStatus target) {
        return target != this && next().contains(target);
    }

    /** A closed finding no longer asks for attention. */
    public boolean isClosed() {
        return this == DISMISSED || this == RESOLVED;
    }
}
