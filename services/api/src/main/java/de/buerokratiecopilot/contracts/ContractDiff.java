package de.buerokratiecopilot.contracts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compares the clauses of two contract versions (spec §13.1, D-002).
 *
 * <p>A pure function over two sets of wordings, deliberately: the interesting question is not "did the
 * file change" but "which term changed, and from what to what". A user has to be able to see the
 * difference and decide whether it matters, and that is impossible if the platform only reports that
 * something differs.</p>
 *
 * <p>The comparison is by clause key, so a renamed heading is a change of the heading, not the
 * disappearance of one clause and the arrival of another — which would hide exactly the change a
 * person is looking for.</p>
 */
public final class ContractDiff {

    /** What happened to one clause between two versions. */
    public enum ChangeType {
        /** The clause is in the new version and was not before. */
        ADDED,
        /** The clause was there and is gone. */
        REMOVED,
        /** The wording differs. */
        CHANGED,
        /** Same key, same wording. */
        UNCHANGED
    }

    /**
     * One difference.
     *
     * @param clauseKey    which clause
     * @param changeType   what happened
     * @param before       the wording in the earlier version, {@code null} when added
     * @param after        the wording in the later version, {@code null} when removed
     * @param significance true when the platform cannot rule out that this matters, which it decides
     *                     only from the clause's own marked criticality, never from a legal judgement
     */
    public record Change(String clauseKey, ChangeType changeType, String before, String after,
                         boolean significance) {

        public Map<String, Object> asMap() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("clauseKey", clauseKey);
            row.put("changeType", changeType.name());
            row.put("before", before);
            row.put("after", after);
            row.put("significance", significance);
            return row;
        }
    }

    /** One clause as it stood in a version. */
    public record ClauseSnapshot(String clauseKey, String text, boolean critical) {
    }

    /** The result of a comparison. */
    public record Result(List<Change> changes) {

        public boolean hasChanges() {
            return changes.stream().anyMatch(change -> change.changeType() != ChangeType.UNCHANGED);
        }

        /** Only the differences a person has to look at. */
        public List<Change> differences() {
            return changes.stream().filter(change -> change.changeType() != ChangeType.UNCHANGED).toList();
        }

        public Map<String, Long> summary() {
            Map<String, Long> counts = new LinkedHashMap<>();
            for (ChangeType type : ChangeType.values()) {
                counts.put(type.name(), changes.stream().filter(change -> change.changeType() == type).count());
            }
            return counts;
        }
    }

    private ContractDiff() {
    }

    /**
     * Compares an earlier clause set with a later one.
     *
     * @param before clauses of the earlier version, or empty when there is nothing to compare against
     * @param after  clauses of the later version
     */
    public static Result compare(List<ClauseSnapshot> before, List<ClauseSnapshot> after) {
        Map<String, ClauseSnapshot> earlier = index(before);
        Map<String, ClauseSnapshot> later = index(after);

        List<Change> changes = new ArrayList<>();
        for (Map.Entry<String, ClauseSnapshot> entry : later.entrySet()) {
            ClauseSnapshot oldClause = earlier.get(entry.getKey());
            ClauseSnapshot newClause = entry.getValue();
            if (oldClause == null) {
                changes.add(new Change(entry.getKey(), ChangeType.ADDED, null, newClause.text(),
                        newClause.critical()));
                continue;
            }
            if (sameText(oldClause.text(), newClause.text())) {
                changes.add(new Change(entry.getKey(), ChangeType.UNCHANGED, oldClause.text(),
                        newClause.text(), false));
                continue;
            }
            changes.add(new Change(entry.getKey(), ChangeType.CHANGED, oldClause.text(), newClause.text(),
                    oldClause.critical() || newClause.critical()));
        }
        for (Map.Entry<String, ClauseSnapshot> entry : earlier.entrySet()) {
            if (!later.containsKey(entry.getKey())) {
                changes.add(new Change(entry.getKey(), ChangeType.REMOVED, entry.getValue().text(), null,
                        entry.getValue().critical()));
            }
        }
        return new Result(List.copyOf(changes));
    }

    private static Map<String, ClauseSnapshot> index(List<ClauseSnapshot> clauses) {
        Map<String, ClauseSnapshot> indexed = new LinkedHashMap<>();
        if (clauses == null) {
            return indexed;
        }
        for (ClauseSnapshot clause : clauses) {
            if (clause == null || clause.clauseKey() == null) {
                continue;
            }
            indexed.put(clause.clauseKey(), clause);
        }
        return indexed;
    }

    /**
     * Whether two wordings are the same.
     *
     * <p>Whitespace and line breaks are normalised first: a document that was re-exported with
     * different wrapping has not changed its terms, and reporting that as a change would bury the
     * change that matters.</p>
     */
    static boolean sameText(String before, String after) {
        if (before == null || after == null) {
            return Objects.equals(before, after);
        }
        return normalise(before).equals(normalise(after));
    }

    private static String normalise(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }
}
