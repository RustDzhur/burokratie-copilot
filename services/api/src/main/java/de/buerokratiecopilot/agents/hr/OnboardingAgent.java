package de.buerokratiecopilot.agents.hr;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.tasks.TaskService;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code hr-onboarding} — Onboarding.
 *
 * <p>Turns a start date and a role into a dated checklist: contract, IT access, workplace, safety
 * briefing, induction plan and the probation review. Each item becomes a task with a completion
 * criterion, because "onboarding done" is not a state a person can tick.</p>
 *
 * <p><strong>Idempotent by title.</strong> An open task with the same title is not created twice;
 * running the agent again after a partly completed onboarding adds only what is missing.</p>
 */
@Component
public class OnboardingAgent implements Agent {

    static final int PROBATION_REVIEW_DAYS = 90;
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final LocalTime MORNING = LocalTime.of(9, 0);
    private static final int MAX_TITLE = 300;

    /** One item of the onboarding checklist. */
    public record PlanTask(String title, String description, int daysOffset,
                           String completionCriteria) {
    }

    private final TaskService tasks;
    private final JdbcTemplate jdbc;

    public OnboardingAgent(TaskService tasks, JdbcTemplate jdbc) {
        this.tasks = tasks;
        this.jdbc = jdbc;
    }

    @Override
    public String id() {
        return "hr-onboarding";
    }

    @Override
    public AgentResult run(AgentContext context) {
        String employee = context.input("employeeName");
        String startRaw = context.input("startDate");
        if (employee == null || employee.isBlank() || startRaw == null || startRaw.isBlank()) {
            return AgentResult.skipped("Für den Onboarding-Plan werden Name und Startdatum "
                    + "benötigt.");
        }
        LocalDate start;
        try {
            start = LocalDate.parse(startRaw.strip());
        } catch (DateTimeParseException e) {
            return AgentResult.failed("Das Startdatum ist ungültig: " + startRaw);
        }
        String role = blankTo(context.input("role"), "Mitarbeitende/r");
        String department = blankTo(context.input("department"), "ohne Abteilung");
        String externalKey = blankTo(context.input("externalKey"), "manual");
        List<String> tags = List.of("onboarding", externalKey.toLowerCase(Locale.ROOT));

        List<PlanTask> plan = plan(employee.strip(), role, department);
        int created = 0;
        int existing = 0;
        for (PlanTask item : plan) {
            String title = truncate(item.title());
            Integer open = jdbc.queryForObject("""
                    select count(*) from tasks
                    where tenant_id = ? and deleted_at is null and title = ?
                      and status not in ('DONE', 'CANCELLED')
                    """, Integer.class, context.tenantId(), title);
            if (open != null && open > 0) {
                existing++;
                continue;
            }
            Task task = tasks.create(new TaskService.CreateRequest(context.tenantId(), null, null,
                    title, item.description(), Task.Priority.NORMAL, "RULE",
                    "Onboarding-Schritt aus dem hinterlegten Plan", dueAt(start, item.daysOffset()),
                    null, item.completionCriteria(), false, null, null, tags, Task.Source.SYSTEM,
                    null), context.actorUserId());
            context.addProduced("task", task.getId().toString(),
                    title + " (fällig " + start.plusDays(item.daysOffset()) + ")");
            created++;
        }

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("employee", employee.strip());
        meta.put("startDate", start.toString());
        meta.put("created", String.valueOf(created));
        meta.put("alreadyOpen", String.valueOf(existing));
        if (created == 0) {
            return AgentResult.skipped("Alle Onboarding-Aufgaben für " + employee.strip()
                    + " sind bereits offen; es wurde nichts doppelt angelegt.");
        }
        String summary = "Onboarding-Plan für " + employee.strip() + " ab " + start + ": "
                + created + " Aufgabe(n) angelegt"
                + (existing > 0 ? ", " + existing + " bereits vorhanden" : "") + ".";
        return AgentResult.done(summary, context.produced(), meta);
    }

    /** The checklist as data, independent of the database and the start date. Visible for tests. */
    public static List<PlanTask> plan(String employeeName, String role, String department) {
        String prefix = "Onboarding " + employeeName + ": ";
        List<PlanTask> plan = new ArrayList<>();
        plan.add(new PlanTask(prefix + "Vertragsunterlagen prüfen",
                "Arbeitsvertrag, Anlagen und Nachweise für " + employeeName + " ("
                        + department + ") prüfen und in der Personalakte ablegen.",
                -5, "Unterschriebener Vertrag liegt vollständig in der Personalakte."));
        plan.add(new PlanTask(prefix + "IT-Zugang einrichten",
                "Konto, E-Mail, Rolle als " + role + " und benötigte Anwendungen einrichten.",
                -3, "Zugang ist getestet und die Rolle im System nachweisbar gesetzt."));
        plan.add(new PlanTask(prefix + "Arbeitsplatz vorbereiten",
                "Arbeitsplatz, Schlüssel und Arbeitsmittel für den ersten Tag bereitstellen.",
                -2, "Arbeitsplatz ist eingerichtet und die Übergabe dokumentiert."));
        plan.add(new PlanTask(prefix + "Sicherheitsunterweisung",
                "Unterweisung zu Datenschutz, IT-Sicherheit und Arbeitsschutz durchführen.",
                0, "Unterweisung ist durchgeführt und unterschrieben abgelegt."));
        plan.add(new PlanTask(prefix + "Einarbeitungsplan abstimmen",
                "Einarbeitungsplan mit Führungskraft und Paten besprechen und festhalten.",
                0, "Plan ist abgestimmt und im Vorgang hinterlegt."));
        plan.add(new PlanTask(prefix + "Probezeitgespräch",
                "Rückblick auf die Probezeit, Zielvereinbarung und Entscheidung vorbereiten.",
                PROBATION_REVIEW_DAYS,
                "Gespräch ist geführt und das Ergebnis dokumentiert."));
        return List.copyOf(plan);
    }

    private static java.time.Instant dueAt(LocalDate start, int offset) {
        return start.plusDays(offset).atTime(MORNING).atZone(BERLIN).toInstant();
    }

    private static String truncate(String value) {
        return value.length() <= MAX_TITLE ? value : value.substring(0, MAX_TITLE - 3) + "...";
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
