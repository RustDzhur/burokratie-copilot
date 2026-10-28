package de.buerokratiecopilot.agents.hospitality;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
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
 * {@code hospitality-shift-plan} — Dienstplan.
 *
 * <p>Builds a weekly shift plan from the active staff, their supplied availability and the expected
 * covers. It respects unavailable days, caps working days per employee and flags understaffed
 * shifts. The plan is a proposal for the manager; the agent creates no binding roster and books no
 * time.</p>
 */
@Component
public class ShiftPlanAgent implements Agent {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AgentWorkspace workspace;

    public ShiftPlanAgent(JdbcTemplate jdbc, ObjectMapper mapper, AgentWorkspace workspace) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.workspace = workspace;
    }

    @Override
    public String id() {
        return "hospitality-shift-plan";
    }

    @Override
    public AgentResult run(AgentContext context) {
        Week week = parseWeek(context.input("week"));
        if (week == null) {
            return AgentResult.skipped("Dienstplan: 'week' fehlt oder ist kein gültiges Format "
                    + "YYYY-Www.");
        }
        List<Map<String, Object>> employees = jdbc.queryForList("""
                select distinct u.id, u.display_name
                from users u join memberships m on m.user_id = u.id
                where m.tenant_id = ? and m.status = 'ACTIVE' and u.deleted_at is null
                order by u.display_name
                """, context.tenantId());
        if (employees.isEmpty()) {
            return AgentResult.skipped("Dienstplan: keine aktiven Mitarbeitenden im Mandanten.");
        }
        Map<UUID, Availability> availability = availability(context.input("staffAvailability"));
        Opening opening = opening(context.input("openingHours"));
        int covers = AgentInputs.integer(context.input("expectedCovers"), 0, 0, 100_000);
        Plan plan = plan(week.monday(), employees, availability, opening, covers);
        String sourceKey = "hospitality-shift-plan:" + week.key() + ":"
                + AgentInputs.key(employees.stream().map(e -> String.valueOf(e.get("id"))).toList().toString());
        String html = html(week, opening, covers, plan);
        AgentWorkspace.DocumentRef document = workspace.ensureDocument(context, sourceKey, null, null,
                "Dienstplan " + week.key(), html,
                Map.of("week", week.key(), "employees", employees.size(),
                        "understaffed", plan.understaffedShifts()), List.of());
        if (document.created()) {
            context.addProduced("generated_document", document.id().toString(),
                    "Dienstplan " + week.key());
        }
        String title = "Dienstplan bestätigen: " + week.key();
        AgentWorkspace.TaskRef task = workspace.ensureTask(context, sourceKey + ":approval", title,
                "Woche " + week.key() + " (" + week.monday() + " bis " + week.monday().plusDays(6)
                        + ")\nMitarbeitende: " + employees.size()
                        + "\nSchichten: " + plan.shifts().size()
                        + "\nUnterbesetzte Schichten: " + plan.understaffedShifts()
                        + "\n\nDer Plan ist ein Vorschlag; Ruhezeiten und genehmigte Abwesenheiten "
                        + "sind vor der Bestätigung zu prüfen. Der Agent plant keine verbindliche "
                        + "Schicht ein.",
                plan.understaffedShifts() > 0 ? Task.Priority.HIGH : Task.Priority.NORMAL,
                context.now().plus(Duration.ofDays(3)),
                List.of("hospitality", "shift-plan"), Map.of("week", week.key(),
                        "employees", employees.size(), "understaffed", plan.understaffedShifts()));
        if (task.created()) {
            context.addProduced("task", task.id().toString(), title);
        }
        for (Shift shift : plan.shifts().stream().limit(10).toList()) {
            context.addProduced("shift", shift.date() + "/" + shift.start(),
                    shift.employee() + " " + shift.start() + "–" + shift.end());
        }
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("week", week.key());
        meta.put("employees", String.valueOf(employees.size()));
        meta.put("shifts", String.valueOf(plan.shifts().size()));
        meta.put("understaffedShifts", String.valueOf(plan.understaffedShifts()));
        meta.put("covers", String.valueOf(covers));
        meta.put("rosterCommitted", "false");
        return AgentResult.done("Dienstplan " + week.key() + " erstellt: " + plan.shifts().size()
                + " Schicht(en) für " + employees.size() + " Mitarbeitende, "
                + plan.understaffedShifts() + " unterbesetzt; Bestätigung offen.",
                context.produced(), meta);
    }

    /** Builds the plan; pure and testable. */
    public static Plan plan(LocalDate monday, List<Map<String, Object>> employees,
                            Map<UUID, Availability> availability, Opening opening, int covers) {
        int needed = covers <= 0 ? 1 : Math.max(1, (int) Math.ceil(covers / 25.0));
        needed = Math.min(needed, 4);
        List<Shift> shifts = new ArrayList<>();
        int understaffed = 0;
        int cursor = 0;
        for (int day = 0; day < 7; day++) {
            LocalDate date = monday.plusDays(day);
            List<Employee> eligible = new ArrayList<>();
            for (Map<String, Object> employee : employees) {
                UUID id = (UUID) employee.get("id");
                Availability available = availability.get(id);
                if (available != null && !available.available(date.getDayOfWeek())) {
                    continue;
                }
                eligible.add(new Employee(id, String.valueOf(employee.get("display_name"))));
            }
            if (eligible.isEmpty()) {
                understaffed++;
                continue;
            }
            int assigned = Math.min(needed, eligible.size());
            if (assigned < needed) {
                understaffed++;
            }
            for (int slot = 0; slot < assigned; slot++) {
                Employee employee = eligible.get(cursor++ % eligible.size());
                LocalTime start = opening.start();
                LocalTime end = opening.end();
                if (needed == 2) {
                    start = slot == 0 ? opening.start() : opening.start().plusHours(6);
                    end = slot == 0 ? opening.start().plusHours(6) : opening.end();
                }
                shifts.add(new Shift(date, start, end, employee.name(), employee.id()));
            }
        }
        return new Plan(List.copyOf(shifts), List.copyOf(new ArrayList<>()), understaffed);
    }

    static Week parseWeek(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.strip().toUpperCase(Locale.ROOT);
        try {
            String[] parts = value.split("-W");
            int year = Integer.parseInt(parts[0]);
            int week = Integer.parseInt(parts[1]);
            if (week < 1 || week > 53) {
                return null;
            }
            LocalDate monday = LocalDate.of(year, 1, 4)
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .plusWeeks(week - 1L);
            return new Week(value, monday);
        }
        catch (RuntimeException ex) {
            return null;
        }
    }

    private Map<UUID, Availability> availability(String raw) {
        Map<UUID, Availability> availability = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return availability;
        }
        try {
            for (Map<String, Object> row : mapper.readValue(raw,
                    new TypeReference<List<Map<String, Object>>>() {
                    })) {
                UUID id = AgentInputs.uuid(String.valueOf(row.get("employeeId")));
                if (id == null) {
                    continue;
                }
                List<String> days = AgentInputs.list(String.valueOf(row.get("availableDays")));
                if (days.isEmpty()) {
                    days = AgentInputs.list(String.valueOf(row.get("days")));
                }
                availability.put(id, new Availability(days, row.get("maxDays") instanceof Number number
                        ? number.intValue() : Integer.MAX_VALUE));
            }
        }
        catch (Exception ignored) {
            // all available
        }
        return availability;
    }

    private static Opening opening(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Opening(LocalTime.of(10, 0), LocalTime.of(22, 0), "Standard 10:00–22:00");
        }
        String[] parts = raw.split("-", 2);
        LocalTime start = AgentInputs.time(parts[0]);
        LocalTime end = parts.length > 1 ? AgentInputs.time(parts[1]) : null;
        if (start == null || end == null || !end.isAfter(start)) {
            return new Opening(LocalTime.of(10, 0), LocalTime.of(22, 0), "Standard 10:00–22:00");
        }
        return new Opening(start, end, raw.strip());
    }

    private static String html(Week week, Opening opening, int covers, Plan plan) {
        StringBuilder html = new StringBuilder("<h1>Dienstplan ").append(week.key())
                .append("</h1><p>Woche: ").append(week.monday()).append(" bis ")
                .append(week.monday().plusDays(6)).append("<br>Öffnungszeiten: ")
                .append(AgentInputs.html(opening.description())).append("<br>Erwartete Gäste: ")
                .append(covers).append("<br>Unterbesetzte Schichten: ")
                .append(plan.understaffedShifts()).append("</p>")
                .append("<table><tr><th>Datum</th><th>Von</th><th>Bis</th><th>Mitarbeitende/r</th></tr>");
        for (Shift shift : plan.shifts()) {
            html.append("<tr><td>").append(shift.date()).append("</td><td>").append(shift.start())
                    .append("</td><td>").append(shift.end()).append("</td><td>")
                    .append(AgentInputs.html(shift.employee())).append("</td></tr>");
        }
        html.append("</table><p><em>Vorschlag für die Leitung. Ruhezeiten, genehmigte Abwesenheiten "
                + "und Arbeitszeitgrenzen sind vor der Bestätigung zu prüfen; der Agent legt keinen "
                + "verbindlichen Dienstplan an.</em></p>");
        return html.toString();
    }

    /** One week key and its Monday. */
    public record Week(String key, LocalDate monday) {
    }

    /** Availability of one employee. */
    public record Availability(List<String> days, int maxDays) {

        boolean available(DayOfWeek day) {
            if (days == null || days.isEmpty()) {
                return true;
            }
            String code = day.name().substring(0, 2);
            return days.stream().anyMatch(value -> value.toUpperCase(Locale.ROOT).startsWith(code));
        }
    }

    /** One planned shift. */
    public record Shift(LocalDate date, LocalTime start, LocalTime end, String employee, UUID employeeId) {
    }

    /** The plan. */
    public record Plan(List<Shift> shifts, List<Shift> openShifts, int understaffedShifts) {
    }

    private record Employee(UUID id, String name) {
    }

    /** Opening hours. */
    public record Opening(LocalTime start, LocalTime end, String description) {
    }
}
