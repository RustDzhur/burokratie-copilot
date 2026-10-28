package de.buerokratiecopilot.importer;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Decides whether a file has finished being written (spec §21, G-003 acceptance).
 *
 * <p>The acceptance criterion is that a file being appended to at the moment of import does not enter the
 * system partially. A half-imported scan is worse than a missing one: it looks like a document, it gets a
 * content hash, it may be classified and filed, and nobody notices the page that was not there yet.</p>
 *
 * <p>There is no way to ask a file whether it is finished. What can be observed is that it is no longer
 * changing, and that is what this class does: two observations, separated in time, of size and
 * modification time. If both are identical, the writer has stopped — for now.</p>
 *
 * <p>Three things make the answer honest rather than optimistic:</p>
 *
 * <ul>
 *   <li><strong>Two observations.</strong> One look cannot tell a finished file from one that is about to
 *       grow; the first look only establishes a baseline.</li>
 *   <li><strong>A wait.</strong> Scanners and network shares flush in bursts, so a file can be unchanged
 *       for a moment in the middle of being written. The wait is what makes the observation mean
 *       something.</li>
 *   <li><strong>An empty file is never ready.</strong> A file that exists and holds nothing is a file
 *       whose writer has not started, and importing it would create an empty document that a person then
 *       has to find and delete.</li>
 * </ul>
 *
 * <p>What this cannot catch is a writer that pauses longer than the wait. That is why the import also
 * compares the hash before and after reading: the guarantee is completed by refusing the file, not by
 * trusting the observation.</p>
 */
public final class FileStability {

    /** The length of time a file must be unchanged before it is considered finished. */
    public static final Duration DEFAULT_WAIT = Duration.ofSeconds(2);

    /** An observation of a file at a moment. */
    public record Snapshot(String name, long sizeBytes, long modifiedAtMillis) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("sizeBytes", sizeBytes);
            map.put("modifiedAtMillis", modifiedAtMillis);
            return map;
        }
    }

    /** Why a file was not accepted. */
    public enum Verdict {
        /** Unchanged across the wait and not empty. */
        READY,
        /** Still growing, or the first observation. */
        STILL_WRITING,
        /** Nothing in it, so its writer has not started. */
        EMPTY,
        /** The file disappeared between the two observations. */
        GONE
    }

    private FileStability() {
    }

    /**
     * Compares two observations.
     *
     * @param first  the earlier observation, or {@code null} when this is the first look
     * @param second the later observation, or {@code null} when the file is gone
     */
    public static Verdict verdict(Snapshot first, Snapshot second) {
        if (second == null) {
            return Verdict.GONE;
        }
        if (second.sizeBytes() == 0) {
            // A file that exists and holds nothing is one whose writer has not started.
            return Verdict.EMPTY;
        }
        if (first == null) {
            return Verdict.STILL_WRITING;
        }
        boolean unchanged = first.sizeBytes() == second.sizeBytes()
                && first.modifiedAtMillis() == second.modifiedAtMillis();
        return unchanged ? Verdict.READY : Verdict.STILL_WRITING;
    }

    /** Whether the verdict means the file may be imported. */
    public static boolean isReady(Verdict verdict) {
        return verdict == Verdict.READY;
    }

    /**
     * Whether a file changed while it was being read.
     *
     * <p>The second half of the guarantee: a writer may pause longer than the wait, and the file then
     * looks finished. Reading it and comparing what was read with what is on disk afterwards catches that
     * case, and the import is abandoned rather than half-done.</p>
     */
    public static boolean changedWhileReading(Snapshot before, Snapshot after) {
        if (before == null || after == null) {
            return true;
        }
        return before.sizeBytes() != after.sizeBytes()
                || before.modifiedAtMillis() != after.modifiedAtMillis();
    }

    /**
     * Whether two observations describe the same file.
     *
     * <p>Name and size together are enough to tell a replacement from a change; comparing only the name
     * would treat a new file written under the same name as the old one.</p>
     */
    public static boolean isSameFile(Snapshot first, Snapshot second) {
        return first != null && second != null && first.name().equals(second.name());
    }

    /** A description a user can act on when a file was not imported. */
    public static String explain(Verdict verdict, String name) {
        return switch (verdict) {
            case READY -> null;
            case STILL_WRITING -> "Die Datei '" + name + "' wird noch geschrieben und wurde noch nicht "
                    + "übernommen. Ein halb übernommener Scan sieht wie ein Dokument aus.";
            case EMPTY -> "Die Datei '" + name + "' ist leer; ihr Schreiber hat noch nicht begonnen.";
            case GONE -> "Die Datei '" + name + "' ist zwischen zwei Beobachtungen verschwunden.";
        };
    }

    /** A content hash must match this before a document is created, as the documents table requires. */
    public static boolean looksLikeContentHash(String hash) {
        return hash != null && hash.matches("^[0-9a-f]{64}$");
    }
}
