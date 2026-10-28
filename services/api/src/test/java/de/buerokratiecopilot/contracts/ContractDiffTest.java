package de.buerokratiecopilot.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.contracts.ContractDiff.Change;
import de.buerokratiecopilot.contracts.ContractDiff.ChangeType;
import de.buerokratiecopilot.contracts.ContractDiff.ClauseSnapshot;
import de.buerokratiecopilot.contracts.ContractDiff.Result;

/**
 * Verifies the comparison of two contract versions (spec §13.1, D-002 acceptance).
 *
 * <p>The acceptance criterion is that a change to a term in a new version is visible to the user. What
 * that requires of the comparison is precision: it has to name the clause, show both wordings, and not
 * bury the real change under formatting noise or under a spurious "removed and added" pair.</p>
 */
class ContractDiffTest {

    private static ClauseSnapshot clause(String key, String text) {
        return new ClauseSnapshot(key, text, false);
    }

    @Test
    @DisplayName("a changed term is reported with both wordings")
    void reportsAChangedTerm() {
        Result result = ContractDiff.compare(
                List.of(clause("NOTICE_PERIOD", "Der Vertrag kann mit einer Frist von drei Monaten "
                        + "gekündigt werden.")),
                List.of(clause("NOTICE_PERIOD", "Der Vertrag kann mit einer Frist von sechs Monaten "
                        + "gekündigt werden.")));

        List<Change> differences = result.differences();
        assertThat(differences).hasSize(1);
        assertThat(differences.getFirst().clauseKey()).isEqualTo("NOTICE_PERIOD");
        assertThat(differences.getFirst().changeType()).isEqualTo(ChangeType.CHANGED);
        assertThat(differences.getFirst().before()).contains("drei Monaten");
        assertThat(differences.getFirst().after()).contains("sechs Monaten");
        assertThat(result.hasChanges()).isTrue();
        assertThat(result.summary()).containsEntry("CHANGED", 1L);
    }

    @Test
    @DisplayName("an added and a removed clause are told apart from a changed one")
    void distinguishesAddedAndRemoved() {
        Result result = ContractDiff.compare(
                List.of(clause("A", "Alt"), clause("B", "Bleibt")),
                List.of(clause("B", "Bleibt"), clause("C", "Neu")));

        assertThat(result.differences()).extracting(Change::changeType)
                .containsExactlyInAnyOrder(ChangeType.ADDED, ChangeType.REMOVED);
        assertThat(result.differences()).extracting(Change::clauseKey)
                .containsExactlyInAnyOrder("A", "C");

        Change added = result.differences().stream()
                .filter(change -> change.changeType() == ChangeType.ADDED).findFirst().orElseThrow();
        assertThat(added.before()).isNull();
        assertThat(added.after()).isEqualTo("Neu");
    }

    @Test
    @DisplayName("a renamed heading is not a removed clause and a new one")
    void comparisonIsByKeyNotByPosition() {
        // The same clause moves to the front of the document; that is not a change of its terms.
        Result result = ContractDiff.compare(
                List.of(clause("FIRST", "Inhalt A"), clause("SECOND", "Inhalt B")),
                List.of(clause("SECOND", "Inhalt B"), clause("FIRST", "Inhalt A")));

        assertThat(result.differences()).isEmpty();
        assertThat(result.hasChanges()).isFalse();
    }

    @Test
    @DisplayName("re-wrapped text is not a changed term")
    void ignoresFormattingDifferences() {
        Result result = ContractDiff.compare(
                List.of(clause("X", "Die Haftung ist auf Vorsatz und grobe Fahrlässigkeit begrenzt.")),
                List.of(clause("X", "Die Haftung ist auf Vorsatz\nund grobe Fahrlässigkeit   begrenzt.")));

        assertThat(result.differences())
                .as("a re-exported document has not changed its terms")
                .isEmpty();
        assertThat(result.changes()).hasSize(1);
        assertThat(result.changes().getFirst().changeType()).isEqualTo(ChangeType.UNCHANGED);
    }

    @Test
    @DisplayName("a change to a critical clause is marked as significant; the platform does not judge it")
    void marksSignificanceFromCriticalityOnly() {
        Result result = ContractDiff.compare(
                List.of(new ClauseSnapshot("LIABILITY", "Haftung begrenzt auf 1.000 EUR", true)),
                List.of(new ClauseSnapshot("LIABILITY", "Haftung begrenzt auf 100 EUR", true)));

        Change change = result.differences().getFirst();
        assertThat(change.significance())
                .as("marked because the clause is critical, not because the platform read the law")
                .isTrue();
        assertThat(change.changeType()).isEqualTo(ChangeType.CHANGED);
    }

    @Test
    @DisplayName("a change to an ordinary clause is not dressed up as significant")
    void ordinaryChangesAreNotInflated() {
        Result result = ContractDiff.compare(
                List.of(clause("SERVICE_SCOPE", "Wartung monatlich")),
                List.of(clause("SERVICE_SCOPE", "Wartung vierteljährlich")));

        assertThat(result.differences().getFirst().significance()).isFalse();
    }

    @Test
    @DisplayName("everything is new when there is no earlier version")
    void firstVersionReportsAdditions() {
        Result result = ContractDiff.compare(List.of(),
                List.of(clause("A", "Erster Inhalt"), clause("B", "Zweiter Inhalt")));

        assertThat(result.differences()).hasSize(2);
        assertThat(result.differences()).allSatisfy(change ->
                assertThat(change.changeType()).isEqualTo(ChangeType.ADDED));
        assertThat(result.summary()).containsEntry("ADDED", 2L);
    }

    @Test
    @DisplayName("deleting every clause is reported as removals, not as no changes")
    void emptyLaterVersionReportsRemovals() {
        Result result = ContractDiff.compare(List.of(clause("A", "Inhalt")), List.of());

        assertThat(result.differences()).hasSize(1);
        assertThat(result.differences().getFirst().changeType()).isEqualTo(ChangeType.REMOVED);
        assertThat(result.hasChanges()).isTrue();
    }

    @Test
    @DisplayName("null inputs are treated as empty rather than throwing")
    void handlesMissingInput() {
        assertThat(ContractDiff.compare(null, null).differences()).isEmpty();
        assertThat(ContractDiff.compare(null, List.of(clause("A", "x"))).differences()).hasSize(1);
    }

    @Test
    @DisplayName("the summary counts every kind of change, so a report can show the shape of it")
    void summarises() {
        Result result = ContractDiff.compare(
                List.of(clause("A", "gleich"), clause("B", "alt"), clause("C", "weg")),
                List.of(clause("A", "gleich"), clause("B", "neu"), clause("D", "dazu")));

        assertThat(result.summary())
                .containsEntry("UNCHANGED", 1L)
                .containsEntry("CHANGED", 1L)
                .containsEntry("REMOVED", 1L)
                .containsEntry("ADDED", 1L);
    }
}
