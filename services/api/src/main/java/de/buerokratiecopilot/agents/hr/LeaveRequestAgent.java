package de.buerokratiecopilot.agents.hr;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
 * {@code hr-leave-request} — Urlaubsanträge.
 *
 * <p>Checks a leave request against the employee record, other open leave requests in the same
 * tenant and the named substitute, counts working days and files a decision task with the facts.
 * It does not approve: approval belongs to the responsible manager. Conflicts are shown, not
 * silently resolved.</p>
 */
@Component
public class LeaveRequestAgent implements Agent {

    private final JdbcTemplate jdbc;
    private final AgentWorkspace workspace;

    public LeaveRequestAgent(JdbcTemplate jdbc, AgentWorkspace workspace) {
        this.jdbc = jdbc;
        this.workspace = workspace;
    }

    @Override
    public String id() {
        return "hr-leave-request";
    }

    @Override
    public AgentResult run(AgentContext context) {
        UUID employeeId = AgentInputs.uuid(context.input("employeeId"));
        if (employeeId == null) {
            return AgentResult.skipped("Urlaubsantrag: 'employeeId' fehlt oder ist keine gültige UUID.");
        }
        LocalDate from = AgentInputs.date(context.input("from"));
        LocalDate to = AgentInputs.date(context.input("to"));
        if (from == null || to == null || to.isBefore(from)) {
            return AgentResult.skipped("Urlaubsantrag: 'from' und 'to' sind als gültiger Zeitraum "
                    + "erforderlich.");
        }
        String leaveType = AgentInputs.blankTo(context.input("leaveType"), "URLAUB")
                .toUpperCase(Locale.ROOT);
        if (!List.of("URLAUB", "GLEITZEIT", "UNBEZAHLT").contains(leaveType)) {
            return AgentResult.failed("Urlaubsantrag: unbekannte Antragsart '" + leaveType + "'.");
        }
        List<Map<String, Object>> employees = jdbc.queryForList("""
                select u.id, u.display_name
                from users u
                join memberships m on m.user_id = u.id
                where m.tenant_id = ? and m.status = 'ACTIVE' and u.deleted_at is null and u.id = ?
                """, context.tenantId(), employeeId);
        if (employees.isEmpty()) {
            return AgentResult.skipped("Urlaubsantrag: Mitarbeitende/r " + employeeId
                    + " ist im Mandanten nicht aktiv.");
        }
        String employee = String.valueOf(employees.getFirst().get("display_name"));
        UUID substituteId = AgentInputs.uuid(context.input("substituteUserId"));
        List<String> warnings = new ArrayList<>();
        if (substituteId != null && !employeeExists(context.tenantId(), substituteId)) {
            warnings.add("Vertretung " + substituteId + " ist im Mandanten nicht aktiv");
        }
        if (substituteId == null) {
            warnings.add("keine Vertretung benannt");
        }
        String sourceKey = "hr-leave-request:" + employeeId + ":" + from + ":" + to + ":"
                + leaveType;
        List<Map<String, Object>> overlaps = jdbc.queryForList("""
                select id, title from tasks
                where tenant_id = ? and deleted_at is null and status not in ('DONE', 'CANCELLED')
                  and metadata->>'leaveEmployeeId' = ?
                  and metadata->>'leaveFrom' <= ? and metadata->>'leaveTo' >= ?
                  and coalesce(metadata->>'agentKey', '') <> ?
                """, context.tenantId(), employeeId.toString(), to.toString(), from.toString(),
                sourceKey);
        for (Map<String, Object> overlap : overlaps) {
            warnings.add("Überschneidung mit Antrag " + overlap.get("title"));
        }
        int workingDays = workingDays(from, to);
        String title = "Urlaubsantrag entscheiden: " + employee + " " + from + "–" + to;
        Task.Priority priority = warnings.isEmpty() ? Task.Priority.NORMAL : Task.Priority.HIGH;
        java.time.Instant due = from.minusDays(7).atStartOfDay(AgentInputs.BERLIN).toInstant();
        if (due.isBefore(context.now())) {
            due = context.now().plus(Duration.ofDays(2));
        }
        AgentWorkspace.TaskRef task = workspace.ensureTask(context, sourceKey, title,
                "Mitarbeitende/r: " + employee + " (" + employeeId + ")"
                        + "\nZeitraum: " + from + " bis " + to + " (" + workingDays + " Arbeitstage)"
                        + "\nArt: " + leaveType
                        + "\nVertretung: " + (substituteId == null ? "nicht benannt" : substituteId)
                        + "\nHinweise: " + (warnings.isEmpty() ? "keine"
                                : String.join("; ", warnings))
                        + "\n\nGenehmigung durch die zuständige Führungskraft; der Agent "
                        + "entscheidet nicht.",
                priority, due, List.of("hr", "leave", leaveType.toLowerCase(Locale.ROOT)),
                Map.of("leaveEmployeeId", employeeId.toString(), "leaveFrom", from.toString(),
                        "leaveTo", to.toString(), "leaveType", leaveType,
                        "workingDays", workingDays, "warnings", warnings.size()));
        if (!task.created()) {
            context.addProduced("task", task.id().toString(), "Bestehender Urlaubsantrag");
            return AgentResult.done("Urlaubsantrag für " + employee + " " + from + "–" + to
                    + " ist bereits als Aufgabe vorhanden (" + task.id() + ").",
                    context.produced(), Map.of("existing", "true", "taskId", task.id().toString()));
        }
        context.addProduced("task", task.id().toString(), title);
        String html = "<h1>Urlaubsantrag</h1><p><strong>Mitarbeitende/r:</strong> "
                + AgentInputs.html(employee) + "<br><strong>Zeitraum:</strong> " + from + " bis " + to
                + "<br><strong>Arbeitstage:</strong> " + workingDays + "<br><strong>Art:</strong> "
                + leaveType + "<br><strong>Vertretung:</strong> "
                + (substituteId == null ? "nicht benannt" : substituteId) + "</p>"
                + "<h2>Hinweise zur Entscheidung</h2><ul>"
                + (warnings.isEmpty() ? "<li>keine Überschneidung oder fehlende Angabe erkannt</li>"
                        : warnings.stream().map(w -> "<li>" + AgentInputs.html(w) + "</li>")
                                .reduce("", String::concat))
                + "</ul><p><em>Der Agent prüft und bereitet vor; Genehmigung und Ablehnung sind "
                + "menschliche Entscheidungen.</em></p>";
        AgentWorkspace.DocumentRef document = workspace.ensureDocument(context, sourceKey + ":doc",
                null, null, "Urlaubsantrag " + employee + " " + from, html,
                Map.of("employeeId", employeeId.toString(), "from", from.toString(),
                        "to", to.toString(), "workingDays", workingDays), List.of());
        if (document.created()) {
            context.addProduced("generated_document", document.id().toString(), "Urlaubsantrag");
        }
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("employeeId", employeeId.toString());
        meta.put("from", from.toString());
        meta.put("to", to.toString());
        meta.put("workingDays", String.valueOf(workingDays));
        meta.put("warnings", String.valueOf(warnings.size()));
        meta.put("approved", "false");
        return AgentResult.done("Urlaubsantrag für " + employee + " " + from + "–" + to + " geprüft ("
                + workingDays + " Arbeitstage, " + warnings.size() + " Hinweis(e)); Entscheidung "
                + "offen.", context.produced(), meta);
    }

    /** Working days between two dates, weekends excluded; public holidays stay a manual check. */
    public static int workingDays(LocalDate from, LocalDate to) {
        int days = 0;
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            if (date.getDayOfWeek() != DayOfWeek.SATURDAY
                    && date.getDayOfWeek() != DayOfWeek.SUNDAY) {
                days++;
            }
        }
        return days;
    }

    private boolean employeeExists(UUID tenantId, UUID userId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from memberships
                where tenant_id = ? and user_id = ? and status = 'ACTIVE'
                """, Integer.class, tenantId, userId);
        return count != null && count > 0;
    }
}
