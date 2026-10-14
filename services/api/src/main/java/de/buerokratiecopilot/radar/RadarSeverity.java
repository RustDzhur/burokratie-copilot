package de.buerokratiecopilot.radar;

/**
 * The scale of a finding (spec 6.4, decision D-07). It is not the priority of a task and not the severity of a
 * notification; the mapping to the notification scale is kept here, in one place.
 */
public enum RadarSeverity {
    INFO, LOW, MEDIUM, HIGH, CRITICAL;

    /** The severity of the notification that announces a finding of this severity: INFO, NOTICE, WARNING or CRITICAL. */
    public String notificationSeverity() {
        return switch (this) {
            case INFO, LOW -> "INFO";
            case MEDIUM -> "NOTICE";
            case HIGH -> "WARNING";
            case CRITICAL -> "CRITICAL";
        };
    }

    /** The higher of two severities. */
    public RadarSeverity atLeast(RadarSeverity floor) {
        return ordinal() >= floor.ordinal() ? this : floor;
    }
}
