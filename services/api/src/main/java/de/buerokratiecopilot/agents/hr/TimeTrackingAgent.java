package de.buerokratiecopilot.agents.hr;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
 * {@code hr-time-tracking} — Zeiterfassungs-Prüfung.
 *
 * <p>Checks supplied time entries for implausible breaks, daily and weekly limits and insufficient
 * rest between shifts. The result is a correction list for the employees, not an automatic change.
 * Days without an entry are reported as "not recorded", never as an absence — the platform does not
 * know the individual work schedule.</p>
 */
@Component
public class TimeTrackingAgent implements Agent {

    static final double WEEKLY_LIMIT_HOURS = 48.0;
    static final double REST_HOURS = 11.0;

    private final ObjectMapper mapper;
    private final AgentWorkspace workspace;

    public TimeTrackingAgent(ObjectMapper mapper, AgentWorkspace workspace) {
        this.mapper = mapper;
        this.workspace = workspace;
    }

    @Override
    public String id() {
        return "hr-time-tracking";
    }

    @Override
    public AgentResult run(AgentContext context) {
        LocalDate from = AgentInputs.date(context.input("periodFrom"));
        LocalDate to = AgentInputs.date(context.input("periodTo"));
        if (from == null || to == null || to.isBefore(from)) {
            return AgentResult.skipped("Zeiterfassungs-Prüfung: 'periodFrom' und 'periodTo' sind "
                    + "als gültiger Zeitraum erforderlich.");
        }
        String raw = context.input("timeEntries");
        if (raw == null || raw.isBlank()) {
            return AgentResult.skipped("Zeiterfassungs-Prüfung: keine Zeiteinträge übergeben "
                    + "('timeEntries') — ohne Erfassung gibt es nichts zu prüfen.");
        }
        List<Map<String, Object>> rows;
        try {
            rows = mapper.readValue(raw, new TypeReference<List<Map<String, Object>>>() {
            });
        }
        catch (Exception ex) {
            return AgentResult.failed("Zeiterfassungs-Prüfung: 'timeEntries' ist kein gültiges "
                    + "JSON-Array.");
        }
        double dailyMax = AgentInputs.decimal(context.input("dailyMaxHours")) == null ? 10.0
                : AgentInputs.decimal(context.input("dailyMaxHours")).doubleValue();
        if (dailyMax <= 0 || dailyMax > 24) {
            dailyMax = 10.0;
        }
        int minBreak = AgentInputs.integer(context.input("breakRules"), 30, 0, 240);
        Set<UUID> employeeFilter = new LinkedHashSet<>(AgentInputs.uuidList(context.input("employeeIds")));
        List<Entry> entries = parse(rows, from, to);
        if (employeeFilter != null && !employeeFilter.isEmpty()) {
            entries = entries.stream().filter(e -> e.employeeId() != null
                    && employeeFilter.contains(e.employeeId())).toList();
        }
        if (entries.isEmpty()) {
            return AgentResult.skipped("Zeiterfassungs-Prüfung: keine gültigen Einträge im Zeitraum "
                    + from + " bis " + to + ".");
        }
        List<EmployeeReport> reports = analyse(entries, from, to, dailyMax, minBreak);
        String sourceKey = "hr-time-tracking:" + from + ":" + to + ":" + AgentInputs.key(
                String.join(",", entries.stream().map(e -> e.employeeId() + ":" + e.date()).toList()));
        String html = html(from, to, dailyMax, minBreak, reports);
        AgentWorkspace.DocumentRef document = workspace.ensureDocument(context, sourceKey, null, null,
                "Zeiterfassungs-Prüfung " + from + " bis " + to, html,
                Map.of("from", from.toString(), "to", to.toString(), "entries", entries.size()),
                List.of());
        if (document.created()) {
            context.addProduced("generated_document", document.id().toString(), "Zeitprüfbericht");
        }
        int created = 0;
        for (EmployeeReport report : reports.stream().filter(EmployeeReport::hasFlags).toList()) {
            String title = "Arbeitszeit korrigieren: " + report.employee();
            AgentWorkspace.TaskRef task = workspace.ensureTask(context,
                    sourceKey + ":employee:" + (report.employeeId() == null
                            ? report.employee().toLowerCase() : report.employeeId()), title,
                    "Zeitraum: " + from + " bis " + to + "\nEinträge: " + report.entries()
                            + "\nBefunde:\n- " + String.join("\n- ", report.flags())
                            + "\n\nDer Agent ändert keine Zeiten; Korrektur durch die "
                            + "Beschäftigten bestätigen lassen.",
                    Task.Priority.HIGH, context.now().plus(Duration.ofDays(3)),
                    List.of("hr", "time"), Map.of("employee", report.employee(),
                            "flags", report.flags().size()));
            if (task.created()) {
                created++;
                context.addProduced("task", task.id().toString(), title);
            }
        }
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("entries", String.valueOf(entries.size()));
        meta.put("employees", String.valueOf(reports.size()));
        meta.put("employeesWithFlags", String.valueOf(reports.stream().filter(EmployeeReport::hasFlags).count()));
        meta.put("tasksCreated", String.valueOf(created));
        meta.put("dailyMaxHours", String.valueOf(dailyMax));
        meta.put("autoCorrected", "false");
        long flagged = reports.stream().filter(EmployeeReport::hasFlags).count();
        if (flagged == 0) {
            return AgentResult.done("Zeiterfassung " + from + " bis " + to + ": " + entries.size()
                    + " Eintrag/Einträge geprüft, keine Auffälligkeit bei Pausen, Tages- und "
                    + "Wochengrenzen.", context.produced(), meta);
        }
        return AgentResult.done("Zeiterfassung " + from + " bis " + to + ": " + entries.size()
                + " Eintrag/Einträge geprüft, " + flagged + " Mitarbeitende/r mit Befund, "
                + created + " Korrektur-Aufgabe(n) angelegt.", context.produced(), meta);
    }

    /** The checks as a pure function; visible for tests. */
    public static List<EmployeeReport> analyse(List<Entry> entries, LocalDate from, LocalDate to,
                                               double dailyMax, int minBreak) {
        Map<String, List<Entry>> byEmployee = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (Entry entry : entries) {
            String key = entry.employeeId() == null ? entry.employee() : entry.employeeId().toString();
            byEmployee.computeIfAbsent(key, k -> new ArrayList<>()).add(entry);
            names.putIfAbsent(key, entry.employee());
        }
        List<EmployeeReport> reports = new ArrayList<>();
        for (Map.Entry<String, List<Entry>> group : byEmployee.entrySet()) {
            List<Entry> ofEmployee = group.getValue().stream()
                    .sorted(Comparator.comparing(Entry::date).thenComparing(Entry::start))
                    .toList();
            List<String> flags = new ArrayList<>();
            Map<LocalDate, Double> dailyHours = new LinkedHashMap<>();
            Map<String, Double> weeklyHours = new LinkedHashMap<>();
            for (Entry entry : ofEmployee) {
                if (!entry.end().isAfter(entry.start())) {
                    flags.add(entry.date() + ": Ende liegt nicht nach dem Beginn");
                    continue;
                }
                double minutes = Duration.between(entry.start(), entry.end()).toMinutes()
                        - entry.breakMinutes();
                if (minutes <= 0) {
                    flags.add(entry.date() + ": Pause verbraucht die gesamte Arbeitszeit");
                    continue;
                }
                double hours = minutes / 60.0;
                dailyHours.merge(entry.date(), hours, Double::sum);
                String week = entry.date().with(java.time.temporal.TemporalAdjusters
                        .previousOrSame(DayOfWeek.MONDAY)).toString();
                weeklyHours.merge(week, hours, Double::sum);
                if (hours > dailyMax) {
                    flags.add(entry.date() + ": " + round(hours) + " h über Tagesgrenze "
                            + dailyMax + " h");
                }
                if (hours > 6 && entry.breakMinutes() < minBreak) {
                    flags.add(entry.date() + ": Pause " + entry.breakMinutes()
                            + " Min. unter " + minBreak + " Min. bei über 6 h Arbeit");
                }
            }
            for (Map.Entry<String, Double> week : weeklyHours.entrySet()) {
                if (week.getValue() > WEEKLY_LIMIT_HOURS) {
                    flags.add("Woche ab " + week.getKey() + ": " + round(week.getValue())
                            + " h über " + WEEKLY_LIMIT_HOURS + " h");
                }
            }
            for (int i = 1; i < ofEmployee.size(); i++) {
                Entry previous = ofEmployee.get(i - 1);
                Entry current = ofEmployee.get(i);
                if (previous.end().isAfter(previous.start())
                        && !current.date().equals(previous.date())) {
                    double rest = Duration.between(previous.date().atTime(previous.end()),
                            current.date().atTime(current.start())).toMinutes() / 60.0;
                    if (rest < REST_HOURS) {
                        flags.add(current.date() + ": Ruhezeit " + round(rest)
                                + " h unter " + REST_HOURS + " h");
                    }
                }
            }
            int missing = missingWorkdays(from, to, dailyHours.keySet());
            List<String> deduped = new ArrayList<>(new LinkedHashSet<>(flags));
            reports.add(new EmployeeReport(
                    ofEmployee.getFirst().employeeId(), names.get(group.getKey()),
                    ofEmployee.size(), missing, List.copyOf(deduped)));
        }
        return List.copyOf(reports);
    }

    private static int missingWorkdays(LocalDate from, LocalDate to, Set<LocalDate> recorded) {
        int missing = 0;
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            if (date.getDayOfWeek() != DayOfWeek.SATURDAY
                    && date.getDayOfWeek() != DayOfWeek.SUNDAY && !recorded.contains(date)) {
                missing++;
            }
        }
        return missing;
    }

    private static List<Entry> parse(List<Map<String, Object>> rows, LocalDate from, LocalDate to) {
        List<Entry> entries = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            UUID employeeId = AgentInputs.uuid(text(row.get("employeeId")));
            String employee = AgentInputs.blankTo(text(row.get("employeeName")),
                    employeeId == null ? "unbekannt" : employeeId.toString());
            LocalDate date = AgentInputs.date(text(row.get("date")));
            LocalTime start = AgentInputs.time(text(row.get("start")));
            LocalTime end = AgentInputs.time(text(row.get("end")));
            Integer breakMinutes = row.get("breakMinutes") instanceof Number number
                    ? number.intValue() : AgentInputs.integer(text(row.get("breakMinutes")));
            if (date == null || start == null || end == null || date.isBefore(from)
                    || date.isAfter(to)) {
                continue;
            }
            entries.add(new Entry(employeeId, employee, date, start, end,
                    breakMinutes == null ? 0 : Math.max(0, breakMinutes)));
        }
        return List.copyOf(entries);
    }

    private static String html(LocalDate from, LocalDate to, double dailyMax, int minBreak,
                               List<EmployeeReport> reports) {
        StringBuilder html = new StringBuilder("<h1>Zeiterfassungs-Prüfung ").append(from)
                .append(" bis ").append(to).append("</h1><p>Tagesgrenze: ").append(dailyMax)
                .append(" h, Mindestpause: ").append(minBreak)
                .append(" Min., Wochengrenze: 48 h, Ruhezeit: 11 h.</p>")
                .append("<table><tr><th>Mitarbeitende/r</th><th>Einträge</th>"
                        + "<th>Tage ohne Eintrag</th><th>Befunde</th></tr>");
        for (EmployeeReport report : reports) {
            html.append("<tr><td>").append(AgentInputs.html(report.employee())).append("</td><td>")
                    .append(report.entries()).append("</td><td>").append(report.missingWorkdays())
                    .append("</td><td>").append(report.hasFlags()
                            ? AgentInputs.html(String.join("; ", report.flags()))
                            : "keine").append("</td></tr>");
        }
        html.append("</table><p><em>Tage ohne Eintrag sind keine Fehlzeit, sondern fehlende "
                + "Erfassung. Der Agent ändert keine Zeiten; die Liste dient der Korrektur durch die "
                + "Beschäftigten. Keine Rechtsberatung.</em></p>");
        return html.toString();
    }

    private static String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).strip();
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }

    /** One time entry. */
    public record Entry(UUID employeeId, String employee, LocalDate date, LocalTime start,
                        LocalTime end, int breakMinutes) {
    }

    /** The checks for one employee. */
    public record EmployeeReport(UUID employeeId, String employee, int entries, int missingWorkdays,
                                 List<String> flags) {

        public boolean hasFlags() {
            return !flags.isEmpty();
        }
    }
}
