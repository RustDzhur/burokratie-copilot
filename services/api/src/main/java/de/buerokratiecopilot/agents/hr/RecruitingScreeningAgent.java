package de.buerokratiecopilot.agents.hr;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentInputs;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentWorkspace;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code hr-recruiting-screening} — Bewerber-Screening.
 *
 * <p>Compares the extracted text of the supplied application documents with the stated must-have and
 * nice-to-have criteria, ranks by coverage and produces an interview shortlist with the open
 * questions per candidate. There is no automated rejection: candidates outside the shortlist are
 * explicitly left undecided, and missing text is reported instead of scored as zero.</p>
 */
@Component
public class RecruitingScreeningAgent implements Agent {

    private final JdbcTemplate jdbc;
    private final AgentWorkspace workspace;

    public RecruitingScreeningAgent(JdbcTemplate jdbc, AgentWorkspace workspace) {
        this.jdbc = jdbc;
        this.workspace = workspace;
    }

    @Override
    public String id() {
        return "hr-recruiting-screening";
    }

    @Override
    public AgentResult run(AgentContext context) {
        UUID jobId = AgentInputs.uuid(context.input("jobId"));
        if (jobId == null) {
            return AgentResult.skipped("Bewerber-Screening: 'jobId' fehlt oder ist keine gültige UUID.");
        }
        List<UUID> documentIds = AgentInputs.uuidList(context.input("applicationDocuments"));
        if (documentIds.isEmpty()) {
            return AgentResult.skipped("Bewerber-Screening: keine Bewerbungsdokumente übergeben "
                    + "('applicationDocuments').");
        }
        List<String> mustHave = AgentInputs.list(context.input("mustHaveCriteria"));
        if (mustHave.isEmpty()) {
            return AgentResult.skipped("Bewerber-Screening: 'mustHaveCriteria' fehlt — ohne "
                    + "Anforderungen wäre jede Bewertung geraten.");
        }
        List<String> niceToHave = AgentInputs.list(context.input("niceToHaveCriteria"));
        int maxCandidates = AgentInputs.integer(context.input("maxCandidates"), 10, 1, 50);

        String placeholders = String.join(",", documentIds.stream().map(id -> "?").toList());
        List<Object> params = new ArrayList<>(List.of(context.tenantId()));
        params.addAll(documentIds);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, original_filename, coalesce(extracted_text, '') as extracted_text,
                       coalesce(summary, '') as summary
                from documents
                where tenant_id = ? and deleted_at is null and id in (%s)
                """.formatted(placeholders), params.toArray());
        if (rows.isEmpty()) {
            return AgentResult.skipped("Bewerber-Screening: keines der angegebenen Dokumente ist "
                    + "im Mandanten vorhanden.");
        }
        List<Candidate> candidates = new ArrayList<>();
        int withoutText = 0;
        for (Map<String, Object> row : rows) {
            String text = (String.valueOf(row.get("extracted_text")) + " "
                    + String.valueOf(row.get("summary"))).toLowerCase(Locale.GERMAN);
            if (text.isBlank()) {
                withoutText++;
                continue;
            }
            candidates.add(score((UUID) row.get("id"), String.valueOf(row.get("original_filename")),
                    text, mustHave, niceToHave));
        }
        if (candidates.isEmpty()) {
            return AgentResult.skipped("Bewerber-Screening: für kein Bewerbungsdokument liegt "
                    + "extrahierter Text vor (" + withoutText + " ohne Text) — es wird nicht "
                    + "geraten.");
        }
        candidates.sort(java.util.Comparator.comparingDouble(Candidate::score).reversed());
        List<Candidate> shortlist = candidates.stream().limit(maxCandidates).toList();
        String sourceKey = "hr-recruiting-screening:" + jobId + ":" + AgentInputs.key(
                String.join(",", documentIds.stream().map(UUID::toString).toList()),
                String.join(",", mustHave));
        String html = html(jobId, mustHave, niceToHave, shortlist, candidates.size(), withoutText);
        AgentWorkspace.DocumentRef document = workspace.ensureDocument(context, sourceKey, null, null,
                "Bewerber-Screening " + jobId, html,
                Map.of("jobId", jobId.toString(), "candidates", candidates.size(),
                        "shortlisted", shortlist.size()), List.of());
        if (document.created()) {
            context.addProduced("generated_document", document.id().toString(),
                    "Screening-Shortlist " + jobId);
        }
        int tasks = 0;
        for (Candidate candidate : shortlist) {
            if (candidate.mustMatched() == 0) {
                continue;
            }
            String title = "Interview vorbereiten: " + candidate.filename();
            AgentWorkspace.TaskRef task = workspace.ensureTask(context,
                    sourceKey + ":interview:" + candidate.documentId(), title,
                    "Stelle: " + jobId + "\nBewerbung: " + candidate.filename()
                            + "\nMuss-Kriterien: " + candidate.mustMatched() + "/" + mustHave.size()
                            + "\nWunsch-Kriterien: " + candidate.niceMatched() + "/" + niceToHave.size()
                            + "\nOffene Fragen: " + String.join("; ", candidate.openQuestions()),
                    Task.Priority.NORMAL, context.now().plus(Duration.ofDays(5)),
                    List.of("hr", "recruiting"), Map.of("jobId", jobId.toString(),
                            "documentId", candidate.documentId().toString()));
            if (task.created()) {
                tasks++;
                context.addProduced("task", task.id().toString(), title);
            }
        }
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("jobId", jobId.toString());
        meta.put("documents", String.valueOf(rows.size()));
        meta.put("withoutText", String.valueOf(withoutText));
        meta.put("candidates", String.valueOf(candidates.size()));
        meta.put("shortlisted", String.valueOf(shortlist.size()));
        meta.put("interviewTasksCreated", String.valueOf(tasks));
        meta.put("autoRejected", "0");
        return AgentResult.done("Bewerber-Screening: " + candidates.size() + " Bewerbung(en) "
                + "ausgewertet, " + shortlist.size() + " auf der Interview-Shortlist, "
                + tasks + " Interview-Aufgabe(n) angelegt; niemand automatisch abgelehnt.",
                context.produced(), meta);
    }

    /** Scores one application against the criteria; pure and testable. */
    public static Candidate score(UUID documentId, String filename, String text,
                                  List<String> mustHave, List<String> niceToHave) {
        List<String> missingMust = new ArrayList<>();
        int mustMatched = 0;
        for (String criterion : mustHave) {
            if (matches(text, criterion)) {
                mustMatched++;
            }
            else {
                missingMust.add(criterion);
            }
        }
        int niceMatched = 0;
        for (String criterion : niceToHave) {
            if (matches(text, criterion)) {
                niceMatched++;
            }
        }
        double mustCoverage = mustHave.isEmpty() ? 0 : (double) mustMatched / mustHave.size();
        double niceCoverage = niceToHave.isEmpty() ? 0 : (double) niceMatched / niceToHave.size();
        double score = Math.round((mustCoverage * 0.7 + niceCoverage * 0.3) * 1000) / 10.0;
        List<String> questions = new ArrayList<>();
        for (String missing : missingMust) {
            questions.add("Nachweis zu „" + missing + "“ im Gespräch klären");
        }
        if (questions.isEmpty()) {
            questions.add("Schwerpunkte und Verfügbarkeit im Gespräch bestätigen");
        }
        return new Candidate(documentId, filename, mustMatched, niceMatched, score,
                List.copyOf(questions));
    }

    private static boolean matches(String text, String criterion) {
        for (String alternative : criterion.split("\\|")) {
            String phrase = alternative.strip().toLowerCase(Locale.GERMAN);
            if (phrase.isEmpty()) {
                continue;
            }
            if (text.contains(phrase)) {
                return true;
            }
            String[] words = phrase.split("\\s+");
            if (words.length > 1 && java.util.Arrays.stream(words).allMatch(text::contains)) {
                return true;
            }
        }
        return false;
    }

    private static String html(UUID jobId, List<String> mustHave, List<String> niceToHave,
                               List<Candidate> shortlist, int candidates, int withoutText) {
        StringBuilder html = new StringBuilder("<h1>Bewerber-Screening ").append(jobId)
                .append("</h1><p>Ausgewertete Bewerbungen: ").append(candidates)
                .append(", ohne extrahierten Text: ").append(withoutText)
                .append(", Shortlist: ").append(shortlist.size()).append("</p>")
                .append("<p><strong>Muss-Kriterien:</strong> ")
                .append(AgentInputs.html(String.join(", ", mustHave)))
                .append("<br><strong>Wunsch-Kriterien:</strong> ")
                .append(AgentInputs.html(String.join(", ", niceToHave))).append("</p>")
                .append("<table><tr><th>Bewerbung</th><th>Muss</th><th>Wunsch</th>"
                        + "<th>Abdeckung</th><th>Offene Fragen</th></tr>");
        for (Candidate candidate : shortlist) {
            html.append("<tr><td>").append(AgentInputs.html(candidate.filename())).append("</td><td>")
                    .append(candidate.mustMatched()).append("/").append(mustHave.size())
                    .append("</td><td>").append(candidate.niceMatched()).append("/")
                    .append(niceToHave.size()).append("</td><td>").append(candidate.score())
                    .append("%</td><td>")
                    .append(AgentInputs.html(String.join("; ", candidate.openQuestions())))
                    .append("</td></tr>");
        }
        html.append("</table><p><em>Keine automatisierte Ablehnung. Bewerber außerhalb der "
                + "Shortlist bleiben unentschieden; personenbezogene Daten bleiben im "
                + "Bewerberkontext.</em></p>");
        return html.toString();
    }

    /** One scored application. */
    public record Candidate(UUID documentId, String filename, int mustMatched, int niceMatched,
                            double score, List<String> openQuestions) {
    }
}
