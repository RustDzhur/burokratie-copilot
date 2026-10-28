package de.buerokratiecopilot.radar;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Every move between statuses is decided here, for all 49 pairs. */
class RadarStatusMachineTest {

    private static final Set<String> ALLOWED = Set.of(
            "NEW>UNDER_REVIEW", "NEW>CONFIRMED", "NEW>DISMISSED", "NEW>SNOOZED", "NEW>RESOLVED",
            "UNDER_REVIEW>CONFIRMED", "UNDER_REVIEW>DISMISSED", "UNDER_REVIEW>SNOOZED", "UNDER_REVIEW>RESOLVED",
            "REOPENED>UNDER_REVIEW", "REOPENED>CONFIRMED", "REOPENED>DISMISSED", "REOPENED>SNOOZED", "REOPENED>RESOLVED",
            "CONFIRMED>UNDER_REVIEW", "CONFIRMED>SNOOZED", "CONFIRMED>RESOLVED",
            "SNOOZED>UNDER_REVIEW", "SNOOZED>DISMISSED", "SNOOZED>RESOLVED", "SNOOZED>REOPENED",
            "DISMISSED>REOPENED", "RESOLVED>REOPENED");

    @Test
    @DisplayName("exactly the listed moves are allowed, and no status moves to itself")
    void everyPair() {
        for (RadarFindingStatus from : RadarFindingStatus.values()) {
            for (RadarFindingStatus to : RadarFindingStatus.values()) {
                assertThat(from.canMoveTo(to)).as(from + " -> " + to).isEqualTo(ALLOWED.contains(from + ">" + to));
            }
        }
    }

    @Test
    @DisplayName("nothing moves back to NEW, and a closed finding can only be reopened")
    void closedAndNew() {
        for (RadarFindingStatus from : RadarFindingStatus.values()) {
            assertThat(from.canMoveTo(RadarFindingStatus.NEW)).isFalse();
        }
        assertThat(RadarFindingStatus.RESOLVED.next()).containsExactly(RadarFindingStatus.REOPENED);
        assertThat(RadarFindingStatus.DISMISSED.next()).containsExactly(RadarFindingStatus.REOPENED);
        assertThat(EnumSet.of(RadarFindingStatus.DISMISSED, RadarFindingStatus.RESOLVED))
                .allMatch(RadarFindingStatus::isClosed);
    }

    @Test
    @DisplayName("the severity scale maps to the notification scale (D-07) and a floor can only raise it")
    void severityMapping() {
        assertThat(RadarSeverity.INFO.notificationSeverity()).isEqualTo("INFO");
        assertThat(RadarSeverity.LOW.notificationSeverity()).isEqualTo("INFO");
        assertThat(RadarSeverity.MEDIUM.notificationSeverity()).isEqualTo("NOTICE");
        assertThat(RadarSeverity.HIGH.notificationSeverity()).isEqualTo("WARNING");
        assertThat(RadarSeverity.CRITICAL.notificationSeverity()).isEqualTo("CRITICAL");
        assertThat(RadarSeverity.LOW.atLeast(RadarSeverity.HIGH)).isEqualTo(RadarSeverity.HIGH);
        assertThat(RadarSeverity.CRITICAL.atLeast(RadarSeverity.HIGH)).isEqualTo(RadarSeverity.CRITICAL);
    }
}
