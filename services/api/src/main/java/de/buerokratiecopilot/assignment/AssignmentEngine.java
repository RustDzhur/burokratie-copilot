package de.buerokratiecopilot.assignment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Recommends who should do a task, and says why (spec §12.2).
 *
 * <p>The engine is a pure function of its inputs: rules, candidates and their current workload. That is
 * deliberate — a recommendation nobody can reconstruct is a recommendation nobody can correct, and the
 * specification requires the basis to be visible to the user.</p>
 *
 * <p>The most important behaviour is what happens when nothing fits. The engine returns an empty
 * result rather than the least bad candidate, and the task then waits in the manual distribution queue.
 * Assigning work to somebody who does not match, merely because a choice had to be made, produces a
 * task that looks handled and is not.</p>
 */
public final class AssignmentEngine {

    /**
     * One hint that a person is right for a kind of work.
     *
     * @param roleCode         role the person must hold, or {@code null} for any
     * @param requiredPermission permission the person must have, or {@code null} for any
     * @param caseTypeCode     case type the task must belong to, or {@code null} for any
     * @param weight           0..1, how strong this hint is
     * @param description      shown to the user as part of the basis
     */
    public record Rule(String roleCode, String requiredPermission, String caseTypeCode, double weight,
                       String description) {

        public boolean appliesTo(String caseTypeCode) {
            return this.caseTypeCode == null || this.caseTypeCode.equals(caseTypeCode);
        }
    }

    /**
     * A person who could take the task.
     *
     * @param userId      who
     * @param displayName for the reason a user reads
     * @param roles       roles held in the tenant
     * @param permissions permissions held in the tenant
     * @param openTasks   tasks already open, which is the workload metric
     * @param available   false when the membership has ended or the person is away
     */
    public record Candidate(String userId, String displayName, Set<String> roles,
                            Set<String> permissions, int openTasks, boolean available) {
    }

    /** The task as far as assignment is concerned. */
    public record Subject(String caseTypeCode, String priority) {
    }

    /**
     * Why a person is recommended, in the words shown to the user.
     *
     * @param score between 0 and 1 after the workload penalty
     */
    public record Recommendation(String userId, String displayName, double score,
                                 List<String> reasons) {

        public Map<String, Object> asDiagnostics() {
            return Map.of("userId", userId, "score", Math.round(score * 100) / 100.0,
                    "reasons", String.join("; ", reasons));
        }
    }

    /** What the workload costs a candidate, at most, so it can never outweigh the fit entirely. */
    static final double MAX_WORKLOAD_PENALTY = 0.30;

    /** How much one open task costs. */
    static final double PENALTY_PER_OPEN_TASK = 0.05;

    private AssignmentEngine() {
    }

    /**
     * Recommends a person.
     *
     * @param minimumScore below this nobody is recommended; the caller routes the task to manual
     *                     distribution instead of picking the best of a bad set
     * @return the best candidate, or empty when none reaches the minimum
     */
    public static Optional<Recommendation> recommend(Subject subject, List<Rule> rules,
                                                     List<Candidate> candidates, double minimumScore) {
        List<Recommendation> recommendations = new ArrayList<>();
        for (Candidate candidate : candidates) {
            score(subject, rules, candidate).ifPresent(recommendations::add);
        }
        return recommendations.stream()
                .filter(recommendation -> recommendation.score() >= minimumScore)
                .max(Comparator.comparingDouble(Recommendation::score));
    }

    /** The reasons for recommending one candidate, or empty when they do not fit at all. */
    static Optional<Recommendation> score(Subject subject, List<Rule> rules, Candidate candidate) {
        if (candidate == null || !candidate.available()) {
            // Somebody whose membership ended or who is away is not a candidate, however well they
            // would fit: a task assigned to an absent person is a task nobody does.
            return Optional.empty();
        }

        List<String> reasons = new ArrayList<>();
        double score = 0;

        for (Rule rule : rules) {
            if (!rule.appliesTo(subject.caseTypeCode())) {
                continue;
            }
            if (rule.roleCode() != null && !candidate.roles().contains(rule.roleCode())) {
                continue;
            }
            if (rule.requiredPermission() != null
                    && !candidate.permissions().contains(rule.requiredPermission())) {
                continue;
            }
            score += rule.weight();
            reasons.add(rule.description() + " (+" + format(rule.weight()) + ")");
        }

        if (reasons.isEmpty()) {
            return Optional.empty();
        }

        double penalty = Math.min(MAX_WORKLOAD_PENALTY, candidate.openTasks() * PENALTY_PER_OPEN_TASK);
        if (penalty > 0) {
            score -= penalty;
            reasons.add(candidate.openTasks() + " offene Aufgaben (−" + format(penalty) + ")");
        }

        return Optional.of(new Recommendation(candidate.userId(), candidate.displayName(),
                Math.max(0, Math.min(1, score)), reasons));
    }

    private static String format(double value) {
        return String.valueOf(Math.round(value * 100) / 100.0);
    }

    /** The workload of a candidate, as a fraction of the busiest person, for an overview. */
    public static double relativeLoad(int openTasks, int busiest) {
        if (busiest <= 0) {
            return 0;
        }
        return Math.min(1.0, (double) openTasks / busiest);
    }
}
