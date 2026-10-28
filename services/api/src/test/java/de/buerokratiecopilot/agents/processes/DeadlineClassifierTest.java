package de.buerokratiecopilot.agents.processes;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.agents.processes.DeadlineClassifier.Bucket;

/**
 * The escalation thresholds of {@code deadline-watch}: overdue is strictly before today, "due soon"
 * includes today and the last day of the lead window, and everything beyond is quiet.
 */
class DeadlineClassifierTest {

    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 26);

    @Test
    @DisplayName("the agent id matches the manifest id")
    void idMatchesManifest() {
        assertThat(new DeadlineWatchAgent(null, null, null).id()).isEqualTo("deadline-watch");
    }

    @Test
    @DisplayName("a deadline yesterday is overdue")
    void overdue() {
        assertThat(DeadlineClassifier.classify(LocalDate.of(2026, 10, 25), null, NOW, 7))
                .isEqualTo(Bucket.OVERDUE);
    }

    @Test
    @DisplayName("today and the last day of the window are due soon")
    void dueSoonBoundaries() {
        assertThat(DeadlineClassifier.classify(TODAY, null, NOW, 7))
                .isEqualTo(Bucket.DUE_SOON);
        assertThat(DeadlineClassifier.classify(LocalDate.of(2026, 11, 2), null, NOW, 7))
                .isEqualTo(Bucket.DUE_SOON);
    }

    @Test
    @DisplayName("one day past the window is quiet")
    void beyondWindow() {
        assertThat(DeadlineClassifier.classify(LocalDate.of(2026, 11, 3), null, NOW, 7))
                .isEqualTo(Bucket.OK);
    }

    @Test
    @DisplayName("a deadline given as an instant is classified by its day")
    void classifiesInstant() {
        Instant late = Instant.parse("2026-10-25T22:00:00Z");
        assertThat(DeadlineClassifier.classify(null, late, NOW, 7)).isEqualTo(Bucket.OVERDUE);
    }

    @Test
    @DisplayName("a deadline without any date cannot be watched and is not overdue")
    void withoutDate() {
        assertThat(DeadlineClassifier.classify(null, null, NOW, 7)).isEqualTo(Bucket.OK);
    }
}
