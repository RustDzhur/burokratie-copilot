package de.buerokratiecopilot.agents.processes;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pure comparison of the internal calendar against the external one for {@code calendar-sync}.
 *
 * <p>The platform only ever touches its own files ({@code *@copilot.ics}) on the CalDAV server, so
 * the reconciliation has exactly three verdicts: an internal event whose file is missing remotely
 * was never pushed or was deleted outside; a remote {@code @copilot} file with no internal event is
 * a leftover that a sync would remove (the agent only reports it); everything else is either matched
 * or belongs to somebody else and is counted, never touched.</p>
 */
public final class CalendarReconciler {

    private CalendarReconciler() {
    }

    public record Discrepancies(Set<String> missingRemotely, Set<String> orphanedRemotely,
                                int externalOnly, int matched, int localCount, int remoteOurs) {

        public int discrepancyCount() {
            return missingRemotely.size() + orphanedRemotely.size();
        }

        public java.util.Map<String, Object> asMap() {
            return java.util.Map.of(
                    "localCount", localCount,
                    "matched", matched,
                    "missingRemotely", missingRemotely.size(),
                    "orphanedRemotely", orphanedRemotely.size(),
                    "externalOnly", externalOnly,
                    "missingRemotelyList", new java.util.ArrayList<>(new TreeSet<>(missingRemotely)),
                    "orphanedRemotelyList", new java.util.ArrayList<>(new TreeSet<>(orphanedRemotely)));
        }
    }

    /**
     * @param localFiles  the file names the internal events expect on the server
     * @param remoteHrefs the resource hrefs the server reports (read-only)
     */
    public static Discrepancies reconcile(Set<String> localFiles, List<String> remoteHrefs) {
        Set<String> remoteOurs = new LinkedHashSet<>();
        int externalOnly = 0;
        for (String href : remoteHrefs == null ? List.<String>of() : remoteHrefs) {
            String name = baseName(href);
            if (name != null && name.endsWith("@copilot.ics")) {
                remoteOurs.add(name);
            }
            else {
                externalOnly++;
            }
        }

        Set<String> local = localFiles == null ? Set.of() : localFiles;
        Set<String> missing = new LinkedHashSet<>(local);
        missing.removeAll(remoteOurs);

        Set<String> orphaned = new LinkedHashSet<>(remoteOurs);
        orphaned.removeAll(local);

        int matched = local.size() - missing.size();
        return new Discrepancies(Set.copyOf(missing), Set.copyOf(orphaned), externalOnly, matched,
                local.size(), remoteOurs.size());
    }

    /** The file name of a CalDAV href: everything after the last slash, before any query. */
    static String baseName(String href) {
        if (href == null || href.isBlank()) {
            return null;
        }
        String path = href;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        if (path.endsWith("/")) {
            return null; // a collection, not a file
        }
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
