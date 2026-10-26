package de.buerokratiecopilot.agents.processes;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.agents.processes.CalendarReconciler.Discrepancies;

/**
 * The reconciliation rules of {@code calendar-sync}: only the platform's own {@code @copilot.ics}
 * files are compared, foreign entries are counted and never touched, and every difference is
 * reported without changing anything.
 */
class CalendarReconcilerTest {

    @Test
    @DisplayName("the agent id matches the manifest id")
    void idMatchesManifest() {
        assertThat(new CalendarSyncAgent(null).id())
                .isEqualTo("calendar-sync");
    }

    @Test
    @DisplayName("a file present on both sides is matched")
    void matched() {
        Discrepancies discrepancies = CalendarReconciler.reconcile(
                Set.of("event-1@copilot.ics"),
                List.of("/cal/user/event-1@copilot.ics"));

        assertThat(discrepancies.matched()).isEqualTo(1);
        assertThat(discrepancies.missingRemotely()).isEmpty();
        assertThat(discrepancies.orphanedRemotely()).isEmpty();
        assertThat(discrepancies.discrepancyCount()).isZero();
    }

    @Test
    @DisplayName("an internal event without a remote file is missing remotely")
    void missingRemotely() {
        Discrepancies discrepancies = CalendarReconciler.reconcile(
                Set.of("event-1@copilot.ics", "event-2@copilot.ics"),
                List.of("/cal/user/event-1@copilot.ics"));

        assertThat(discrepancies.missingRemotely()).containsExactly("event-2@copilot.ics");
        assertThat(discrepancies.matched()).isEqualTo(1);
    }

    @Test
    @DisplayName("a remote @copilot file without an internal event is a leftover, only reported")
    void orphanedRemotely() {
        Discrepancies discrepancies = CalendarReconciler.reconcile(
                Set.of("event-1@copilot.ics"),
                List.of("/cal/user/event-1@copilot.ics", "/cal/user/gelöscht@copilot.ics"));

        assertThat(discrepancies.orphanedRemotely()).containsExactly("gelöscht@copilot.ics");
    }

    @Test
    @DisplayName("foreign entries are counted, never compared or touched")
    void externalOnlyCounted() {
        Discrepancies discrepancies = CalendarReconciler.reconcile(
                Set.of("event-1@copilot.ics"),
                List.of("/cal/user/event-1@copilot.ics", "/cal/user/urlaub.ics",
                        "/cal/user/meeting.ics"));

        assertThat(discrepancies.externalOnly()).isEqualTo(2);
        assertThat(discrepancies.missingRemotely()).isEmpty();
        assertThat(discrepancies.orphanedRemotely()).isEmpty();
    }

    @Test
    @DisplayName("hrefs are reduced to their file name, query strings ignored")
    void baseNames() {
        assertThat(CalendarReconciler.baseName("/cal/user/event-1@copilot.ics"))
                .isEqualTo("event-1@copilot.ics");
        assertThat(CalendarReconciler.baseName("https://server/cal/event.ics?x=1"))
                .isEqualTo("event.ics");
        assertThat(CalendarReconciler.baseName("https://server/cal/")).isNull();
        assertThat(CalendarReconciler.baseName(null)).isNull();
    }
}
