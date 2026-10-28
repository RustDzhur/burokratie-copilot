package de.buerokratiecopilot.assignment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.assignment.AssignmentEngine.Candidate;
import de.buerokratiecopilot.assignment.AssignmentEngine.Recommendation;
import de.buerokratiecopilot.assignment.AssignmentEngine.Rule;
import de.buerokratiecopilot.assignment.AssignmentEngine.Subject;

/**
 * Verifies the assignment recommendation (spec §12.2).
 *
 * <p>The engine is deliberately a pure function, so the cases that matter can be written down exactly:
 * the right person wins, the busier of two equally suitable people loses, an absent person is not a
 * candidate, and — the acceptance criterion — when nobody fits, the result is empty rather than the
 * least bad choice.</p>
 */
class AssignmentEngineTest {

    private static final Rule ACCOUNTING = new Rule("ACCOUNTANT", null, "rechnungsklaerung", 0.6,
            "Rolle Buchhaltung passt zum Vorgangstyp");
    private static final Rule LEGAL = new Rule("LEGAL_ADVISOR", null, "kuendigung", 0.6,
            "Rolle Rechtsberatung passt zum Vorgangstyp");
    private static final Rule MANAGER = new Rule("MANAGER", null, null, 0.3, "Leitung");

    private static Candidate candidate(String id, int openTasks, Set<String> roles) {
        return new Candidate(id, "Person " + id, roles, Set.of(), openTasks, true);
    }

    @Test
    @DisplayName("the person whose role matches the case type is recommended, with the reason")
    void recommendsByRoleAndCaseType() {
        Optional<Recommendation> recommendation = AssignmentEngine.recommend(
                new Subject("rechnungsklaerung", "NORMAL"), List.of(ACCOUNTING, LEGAL, MANAGER),
                List.of(candidate("a", 0, Set.of("ACCOUNTANT")),
                        candidate("b", 0, Set.of("LEGAL_ADVISOR"))),
                0.5);

        assertThat(recommendation).isPresent();
        assertThat(recommendation.get().userId()).isEqualTo("a");
        assertThat(recommendation.get().reasons())
                .as("a user must be able to see why this person was suggested")
                .anySatisfy(reason -> assertThat(reason).contains("Buchhaltung"));
    }

    @Test
    @DisplayName("a rule for another case type does not apply")
    void ignoresRulesForOtherCaseTypes() {
        assertThat(AssignmentEngine.recommend(new Subject("kuendigung", "NORMAL"), List.of(ACCOUNTING),
                List.of(candidate("a", 0, Set.of("ACCOUNTANT"))), 0.5))
                .as("the accounting rule is for invoice questions, not for terminations")
                .isEmpty();
    }

    @Test
    @DisplayName("the busier of two equally suitable people is not chosen")
    void workloadBreaksTheTie() {
        Optional<Recommendation> recommendation = AssignmentEngine.recommend(
                new Subject("kuendigung", "NORMAL"), List.of(LEGAL),
                List.of(candidate("busy", 6, Set.of("LEGAL_ADVISOR")),
                        candidate("free", 1, Set.of("LEGAL_ADVISOR"))),
                0.3);

        assertThat(recommendation).isPresent();
        assertThat(recommendation.get().userId()).isEqualTo("free");
        assertThat(recommendation.get().reasons())
                .anySatisfy(reason -> assertThat(reason).contains("offene Aufgaben"));
    }

    @Test
    @DisplayName("the workload can lower a score but never outweigh a qualification")
    void workloadCannotOutweighAFit() {
        // The penalty is capped, so a very busy qualified person still beats an unqualified one.
        Optional<Recommendation> recommendation = AssignmentEngine.recommend(
                new Subject("kuendigung", "NORMAL"), List.of(LEGAL),
                List.of(candidate("busy", 50, Set.of("LEGAL_ADVISOR")),
                        candidate("other", 0, Set.of("ACCOUNTANT"))),
                0.2);

        assertThat(recommendation).isPresent();
        assertThat(recommendation.get().userId()).isEqualTo("busy");
        assertThat(recommendation.get().score()).isGreaterThan(0);
    }

    @Test
    @DisplayName("somebody who is unavailable is not a candidate at all")
    void absentPeopleAreNotCandidates() {
        Candidate away = new Candidate("away", "Abwesend", Set.of("LEGAL_ADVISOR"), Set.of(), 0, false);

        assertThat(AssignmentEngine.recommend(new Subject("kuendigung", "NORMAL"), List.of(LEGAL),
                List.of(away), 0.3))
                .as("assigning work to somebody who is away produces a task nobody does")
                .isEmpty();
    }

    @Test
    @DisplayName("when nobody fits, the task stays unassigned instead of going to the best of a bad set")
    void nobodyFitsMeansManualDistribution() {
        assertThat(AssignmentEngine.recommend(new Subject("kuendigung", "NORMAL"), List.of(LEGAL),
                List.of(candidate("a", 0, Set.of("ACCOUNTANT")),
                        candidate("b", 0, Set.of("EMPLOYEE"))),
                0.3)).isEmpty();
    }

    @Test
    @DisplayName("a candidate below the minimum score is not recommended")
    void respectsTheMinimumScore() {
        assertThat(AssignmentEngine.recommend(new Subject(null, "NORMAL"), List.of(MANAGER),
                List.of(candidate("a", 0, Set.of("MANAGER"))), 0.5)).isEmpty();
        assertThat(AssignmentEngine.recommend(new Subject(null, "NORMAL"), List.of(MANAGER),
                List.of(candidate("a", 0, Set.of("MANAGER"))), 0.3)).isPresent();
    }

    @Test
    @DisplayName("several weak hints can add up to a confident recommendation")
    void weakHintsAccumulate() {
        Rule weakOne = new Rule("MANAGER", null, null, 0.3, "Leitung");
        Rule weakTwo = new Rule(null, "tasks.approve", null, 0.3, "Hat Genehmigungsrecht");
        Candidate candidate = new Candidate("a", "Person a", Set.of("MANAGER"), Set.of("tasks.approve"),
                0, true);

        assertThat(AssignmentEngine.recommend(new Subject(null, "NORMAL"), List.of(weakOne, weakTwo),
                List.of(candidate), 0.5)).isPresent();
        assertThat(AssignmentEngine.recommend(new Subject(null, "NORMAL"), List.of(weakOne),
                List.of(candidate), 0.5)).isEmpty();
    }

    @Test
    @DisplayName("a permission requirement is checked against the person's permissions")
    void requiredPermissionIsChecked() {
        Rule financial = new Rule(null, "finance.approve", null, 0.7, "Braucht Finanzfreigabe");
        Candidate without = new Candidate("a", "Person a", Set.of("MANAGER"), Set.of("tasks.view"), 0,
                true);
        Candidate with = new Candidate("b", "Person b", Set.of("MANAGER"), Set.of("finance.approve"), 0,
                true);

        assertThat(AssignmentEngine.recommend(new Subject(null, "NORMAL"), List.of(financial),
                List.of(without), 0.5)).isEmpty();
        assertThat(AssignmentEngine.recommend(new Subject(null, "NORMAL"), List.of(financial),
                List.of(with), 0.5)).isPresent();
    }

    @Test
    @DisplayName("an empty rule set recommends nobody, which sends the task to manual distribution")
    void noRulesMeansNoRecommendation() {
        assertThat(AssignmentEngine.recommend(new Subject("kuendigung", "NORMAL"), List.of(),
                List.of(candidate("a", 0, Set.of("LEGAL_ADVISOR"))), 0.0)).isEmpty();
    }

    @Test
    @DisplayName("relative load is a fraction of the busiest person")
    void computesRelativeLoad() {
        assertThat(AssignmentEngine.relativeLoad(5, 10)).isEqualTo(0.5);
        assertThat(AssignmentEngine.relativeLoad(0, 10)).isZero();
        assertThat(AssignmentEngine.relativeLoad(3, 0)).isZero();
        assertThat(AssignmentEngine.relativeLoad(20, 10)).isEqualTo(1.0);
    }
}
