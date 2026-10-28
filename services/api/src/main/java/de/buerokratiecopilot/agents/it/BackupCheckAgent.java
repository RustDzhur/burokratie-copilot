package de.buerokratiecopilot.agents.it;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentInputs;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentWorkspace;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code it-backup-check} — Backup-Prüfung.
 *
 * <p>Reads documented backup runs supplied as JSON and checks three things per system: failed runs,
 * gaps longer than the required frequency, and whether a restore was ever tested. The result is a
 * report plus one task per system with a finding. The agent performs no restore and has no access
 * to a backup system.</p>
 */
@Component
public class BackupCheckAgent implements Agent {

    private final ObjectMapper mapper;
    private final AgentWorkspace workspace;

    public BackupCheckAgent(ObjectMapper mapper, AgentWorkspace workspace) {
        this.mapper = mapper;
        this.workspace = workspace;
    }

    @Override
    public String id() {
        return "it-backup-check";
    }

    @Override
    public AgentResult run(AgentContext context) {
        LocalDate from = AgentInputs.date(context.input("periodFrom"));
        LocalDate to = AgentInputs.date(context.input("periodTo"));
        if (from == null || to == null || to.isBefore(from)) {
            return AgentResult.skipped("Backup-Prüfung: 'periodFrom' und 'periodTo' sind als "
                    + "gültiger Zeitraum erforderlich.");
        }
        int frequency = AgentInputs.integer(context.input("requiredFrequencyHours"), 24, 1, 720);
        String raw = context.input("backupRuns");
        if (raw == null || raw.isBlank()) {
            return AgentResult.skipped("Backup-Prüfung: keine dokumentierten Backup-Läufe übergeben "
                    + "('backupRuns'). Ohne Nachweise lässt sich keine Lücke belegen.");
        }
        List<Map<String, Object>> rows;
        try {
            rows = mapper.readValue(raw, new TypeReference<List<Map<String, Object>>>() {
            });
        }
        catch (Exception ex) {
            return AgentResult.failed("Backup-Prüfung: 'backupRuns' ist kein gültiges JSON-Array.");
        }
        List<BackupRun> runs = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String system = text(row.get("system"));
            Instant startedAt = AgentInputs.instant(text(row.get("startedAt")));
            if (system == null || startedAt == null) {
                continue;
            }
            String status = AgentInputs.blankTo(text(row.get("status")), "UNKNOWN")
                    .toUpperCase(Locale.ROOT);
            boolean restoreTested = Boolean.parseBoolean(String.valueOf(row.get("restoreTested")));
            runs.add(new BackupRun(system, startedAt, status, restoreTested,
                    text(row.get("note"))));
        }
        if (runs.isEmpty()) {
            return AgentResult.skipped("Backup-Prüfung: keine verwertbaren Läufe (System und "
                    + "startedAt erforderlich).");
        }
        Set<String> systems = new LinkedHashSet<>();
        for (BackupRun run : runs) {
            systems.add(run.system());
        }
        List<SystemReport> reports = analyse(runs, from, to, frequency, systems);
        int findings = (int) reports.stream().filter(SystemReport::hasFinding).count();
        String sourceKey = "it-backup-check:" + from + ":" + to + ":" + frequency + ":"
                + AgentInputs.key(String.join(",", systems));
        String html = html(from, to, frequency, reports);
        AgentWorkspace.DocumentRef document = workspace.ensureDocument(context, sourceKey, null, null,
                "Backup-Prüfung " + from + " bis " + to, html,
                Map.of("from", from.toString(), "to", to.toString(),
                        "frequencyHours", frequency, "systems", systems.size()), List.of());
        if (document.created()) {
            context.addProduced("generated_document", document.id().toString(), "Backup-Bericht");
        }
        int created = 0;
        for (SystemReport report : reports.stream().filter(SystemReport::hasFinding)
                .limit(20).toList()) {
            String title = "Backup-Nachweis prüfen: " + report.system();
            AgentWorkspace.TaskRef task = workspace.ensureTask(context,
                    sourceKey + ":system:" + report.system().toLowerCase(Locale.ROOT), title,
                    "System " + report.system() + ":\n" + String.join("\n", report.findings())
                            + "\n\nDer Agent führt keine Wiederherstellung durch; Nachweis und "
                            + "Maßnahme durch das IT-Team.",
                    Task.Priority.HIGH, context.now().plus(Duration.ofDays(3)),
                    List.of("it", "backup"),
                    Map.of("system", report.system(), "runCount", report.runCount(),
                            "failedRuns", report.failedRuns(), "gaps", report.gaps().size()));
            if (task.created()) {
                created++;
                context.addProduced("task", task.id().toString(), title);
            }
        }
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("systems", String.valueOf(systems.size()));
        meta.put("runs", String.valueOf(runs.size()));
        meta.put("systemsWithFindings", String.valueOf(findings));
        meta.put("tasksCreated", String.valueOf(created));
        if (findings == 0) {
            return AgentResult.done("Backup-Prüfung " + from + " bis " + to + ": " + runs.size()
                    + " Lauf/Läufe in " + systems.size() + " System(en), keine Ausfälle, keine "
                    + "Lücken, Wiederherstellung getestet.", context.produced(), meta);
        }
        return AgentResult.done("Backup-Prüfung " + from + " bis " + to + ": " + findings + " von "
                + systems.size() + " System(en) mit Befund — " + created + " Aufgabe(n) angelegt.",
                context.produced(), meta);
    }

    /** Analyses the runs per system; pure and testable. */
    public static List<SystemReport> analyse(List<BackupRun> runs, LocalDate from, LocalDate to,
                                             int frequencyHours, Set<String> systems) {
        List<SystemReport> reports = new ArrayList<>();
        Instant start = from.atStartOfDay(AgentInputs.BERLIN).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(AgentInputs.BERLIN).toInstant();
        for (String system : systems) {
            List<BackupRun> ofSystem = runs.stream()
                    .filter(run -> system.equals(run.system()))
                    .filter(run -> !run.startedAt().isBefore(start) && run.startedAt().isBefore(end))
                    .sorted(java.util.Comparator.comparing(BackupRun::startedAt))
                    .toList();
            List<String> findings = new ArrayList<>();
            long failed = ofSystem.stream().filter(run -> !"SUCCESS".equals(run.status())
                    && !"OK".equals(run.status())).count();
            if (failed > 0) {
                findings.add(failed + " fehlgeschlagene(r) Lauf/Läufe");
            }
            List<Gap> gaps = new ArrayList<>();
            if (ofSystem.isEmpty()) {
                findings.add("kein Lauf im Zeitraum dokumentiert");
            }
            else {
                Instant previous = start;
                for (BackupRun run : ofSystem) {
                    long hours = Duration.between(previous, run.startedAt()).toHours();
                    if (hours > frequencyHours) {
                        gaps.add(new Gap(previous, run.startedAt(), hours));
                    }
                    previous = run.startedAt();
                }
                long trailing = Duration.between(previous, end).toHours();
                if (trailing > frequencyHours) {
                    gaps.add(new Gap(previous, end, trailing));
                }
                if (!gaps.isEmpty()) {
                    findings.add(gaps.size() + " Lücke(n) über " + frequencyHours + " Stunden");
                }
                if (ofSystem.stream().noneMatch(BackupRun::restoreTested)) {
                    findings.add("keine getestete Wiederherstellung dokumentiert");
                }
            }
            reports.add(new SystemReport(system, ofSystem.size(), (int) failed, gaps,
                    List.copyOf(findings)));
        }
        return List.copyOf(reports);
    }

    private static String html(LocalDate from, LocalDate to, int frequency,
                               List<SystemReport> reports) {
        StringBuilder html = new StringBuilder("<h1>Backup-Prüfung ").append(from).append(" bis ")
                .append(to).append("</h1><p>Erforderliche Frequenz: ").append(frequency)
                .append(" Stunden. Grundlage: dokumentierte Läufe, kein Zugriff auf das "
                        + "Backup-System.</p><table><tr><th>System</th><th>Läufe</th>"
                        + "<th>Fehlschläge</th><th>Lücken</th><th>Befunde</th></tr>");
        for (SystemReport report : reports) {
            html.append("<tr><td>").append(AgentInputs.html(report.system())).append("</td><td>")
                    .append(report.runCount()).append("</td><td>").append(report.failedRuns())
                    .append("</td><td>").append(report.gaps().size()).append("</td><td>")
                    .append(report.hasFinding()
                            ? AgentInputs.html(String.join("; ", report.findings())) : "keine")
                    .append("</td></tr>");
        }
        html.append("</table><p><em>Fehlende Nachweise sind ein Risiko, keine bewiesene "
                + "Nichtverfügbarkeit. Der Agent führt keine Wiederherstellung durch.</em></p>");
        return html.toString();
    }

    private static String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).strip();
    }

    /** One documented backup run. */
    public record BackupRun(String system, Instant startedAt, String status, boolean restoreTested,
                            String note) {
    }

    /** A gap between two runs (or period edge and first/last run). */
    public record Gap(Instant from, Instant to, long hours) {
    }

    /** The analysis for one system. */
    public record SystemReport(String system, int runCount, int failedRuns, List<Gap> gaps,
                               List<String> findings) {

        public boolean hasFinding() {
            return !findings.isEmpty();
        }
    }
}
