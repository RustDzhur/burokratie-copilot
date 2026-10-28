package de.buerokratiecopilot.agents.hr;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentArtifactStore;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentTaskFactory;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code hr-payroll-data} — Lohndaten-Vorbereitung.
 *
 * <p>Collects what the platform can actually prove for a payroll month: active employees from the
 * membership model, and the HR documents (leave request, sick note, payslip, employment contract)
 * dated inside the month. It calculates <strong>no salary</strong>, no tax and no social
 * contribution — the task says so and hands the package to a human payroll accountant.</p>
 *
 * <p>The platform has no time-tracking and no employee-to-document link. Those gaps are reported as
 * warnings instead of being filled with assumptions. A vague number in payroll data is worse than a
 * stated gap.</p>
 */
@Component
public class HrPayrollDataAgent implements Agent {

    private static final int MAX_EMPLOYEES = 2000;
    private static final int MAX_PRODUCED = 50;
    private static final List<String> HR_DOCUMENT_TYPES = List.of("urlaubsantrag",
            "krankmeldung", "lohnabrechnung", "arbeitsvertrag");

    private final JdbcTemplate jdbc;
    private final AgentArtifactStore artifacts;
    private final AgentTaskFactory tasks;

    public HrPayrollDataAgent(JdbcTemplate jdbc, AgentArtifactStore artifacts,
                              AgentTaskFactory tasks) {
        this.jdbc = jdbc;
        this.artifacts = artifacts;
        this.tasks = tasks;
    }

    @Override
    public String id() {
        return "hr-payroll-data";
    }

    /** One employee as the payroll package lists them. */
    public record Employee(UUID userId, String email, String name, String status,
                           String membershipStatus, String roles) {
    }

    @Override
    public AgentResult run(AgentContext context) {
        YearMonth month;
        try {
            month = YearMonth.parse(require(context.input("month")));
        }
        catch (RuntimeException ex) {
            return AgentResult.skipped("month im Format YYYY-MM fehlt oder ist ungültig; ohne "
                    + "Monat keine Lohndaten.");
        }
        String exportFormat = clean(context.input("exportFormat"));
        exportFormat = exportFormat == null ? "CSV" : exportFormat.toUpperCase(Locale.ROOT);
        if (!"CSV".equals(exportFormat)) {
            return AgentResult.failed("Das Exportformat " + exportFormat + " ist nicht "
                    + "angebunden; unterstützt wird CSV. Ein DATEV-LODAS-Satz wird nicht "
                    + "vorgetäuscht.");
        }
        boolean includeOvertime = !"false".equalsIgnoreCase(context.input("includeOvertime"));
        boolean includeAbsences = !"false".equalsIgnoreCase(context.input("includeAbsences"));
        Set<UUID> employeeFilter = employeeIds(context.input("employeeIds"));

        List<Map<String, Object>> raw = jdbc.queryForList("""
                select u.id, u.email, u.display_name, u.status,
                       m.status as membership_status,
                       coalesce(string_agg(distinct r.code, ','), '') as roles
                from memberships m
                join users u on u.id = m.user_id and u.deleted_at is null
                left join membership_roles mr on mr.membership_id = m.id
                     and (mr.expires_at is null or mr.expires_at > now())
                left join roles r on r.id = mr.role_id
                where m.tenant_id = ? and m.status in ('ACTIVE', 'INVITED')
                group by u.id, u.email, u.display_name, u.status, m.status
                order by u.display_name
                limit ?
                """, context.tenantId(), MAX_EMPLOYEES);
        List<Employee> employees = new ArrayList<>();
        for (Map<String, Object> row : raw) {
            UUID id = (UUID) row.get("id");
            if (employeeFilter != null && !employeeFilter.contains(id)) {
                continue;
            }
            employees.add(new Employee(id, string(row.get("email")), string(row.get("display_name")),
                    string(row.get("status")), string(row.get("membership_status")),
                    string(row.get("roles"))));
        }
        if (employees.isEmpty()) {
            return AgentResult.skipped("Keine aktiven Mitarbeiter im Tenant gefunden; es gibt "
                    + "keine Lohndaten vorzubereiten.");
        }

        Map<String, Integer> documents = includeAbsences ? absenceDocuments(context.tenantId(),
                month) : Map.of();
        List<String> warnings = new ArrayList<>();
        if (includeOvertime) {
            warnings.add("Keine Zeiterfassung angebunden: Überstunden und Zuschläge können nicht "
                    + "aus der Plattform belegt werden.");
        }
        if (includeAbsences && documents.isEmpty()) {
            warnings.add("Keine Urlaubs-/Krankheitsbelege im Monat gefunden; Abwesenheiten sind "
                    + "damit nicht belegt.");
        }
        warnings.add("Abwesenheitsbelege sind nicht mit einem Mitarbeiter verknüpft; eine "
                + "Zuordnung muss die Lohnbuchhaltung anhand der Belege vornehmen.");
        warnings.add("Es werden keine Lohn-, Steuer- oder Sozialversicherungsbeträge berechnet.");

        String content = toCsv(employees, month, documents);
        String sourceKey = "payroll-data:" + month + ":" + exportFormat + ":" + hash(content);
        AgentArtifactStore.Artifact artifact;
        try {
            artifact = artifacts.store(context.tenantId(), context.actorUserId(), sourceKey,
                    "Lohndaten " + month, "lohndaten-" + month + ".csv", "text/csv", "TXT",
                    content);
        }
        catch (RuntimeException ex) {
            return AgentResult.failed("Die Lohndaten konnten nicht gespeichert werden: "
                    + ex.getMessage());
        }

        String title = "Lohnlauf-Daten prüfen: " + month;
        AgentTaskFactory.Created task = tasks.createIfAbsent(context.tenantId(),
                context.actorUserId(), title, description(month, employees, documents, artifact,
                        warnings),
                Task.Priority.NORMAL,
                "Lohndaten sind zusammengestellt, aber nicht berechnet; die Lohnbuchhaltung muss "
                        + "Zuordnung, Belege und Beträge prüfen.",
                context.now().plus(5, ChronoUnit.DAYS),
                List.of("hr", "payroll"));

        context.addProduced("payroll-data", artifact.storageKey(),
                employees.size() + " Mitarbeiter, Monat " + month);
        context.addProduced("task", task.taskId().toString(), title);
        for (String warning : warnings.stream().limit(MAX_PRODUCED).toList()) {
            context.addProduced("payroll-warning", month.toString(), warning);
        }

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("month", month.toString());
        meta.put("employees", String.valueOf(employees.size()));
        meta.put("absenceDocuments", String.valueOf(documents.values().stream()
                .mapToInt(Integer::intValue).sum()));
        meta.put("documentTypes", documents.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + "," + right).orElse(""));
        meta.put("overtimeIncluded", String.valueOf(includeOvertime));
        meta.put("absencesIncluded", String.valueOf(includeAbsences));
        meta.put("exportFormat", exportFormat);
        meta.put("storageKey", artifact.storageKey());
        meta.put("artifactCreated", String.valueOf(artifact.created()));
        meta.put("taskId", task.taskId().toString());
        meta.put("taskCreated", String.valueOf(task.created()));
        meta.put("warnings", String.valueOf(warnings.size()));
        meta.put("limitation", "Keine Zeiterfassung, keine Mitarbeiter-Beleg-Zuordnung und keine "
                + "Lohnberechnung; die Datei ist eine Datensammlung, kein Lohnlauf.");

        return AgentResult.done("Lohndaten " + month + ": " + employees.size()
                        + " Mitarbeiter, " + documents.values().stream()
                        .mapToInt(Integer::intValue).sum() + " HR-Beleg(e), "
                        + warnings.size() + " Hinweis(e). Keine Beträge berechnet; an die "
                        + "Lohnbuchhaltung übergeben.",
                context.produced(), meta);
    }

    private Map<String, Integer> absenceDocuments(UUID tenantId, YearMonth month) {
        LocalDate first = month.atDay(1);
        LocalDate last = month.atEndOfMonth();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select dt.code, count(*) as document_count
                from documents d
                join document_types dt on dt.id = d.document_type_id
                where d.tenant_id = ? and d.deleted_at is null
                  and d.document_date between ? and ?
                  and dt.code in ('urlaubsantrag', 'krankmeldung', 'lohnabrechnung',
                                  'arbeitsvertrag')
                group by dt.code
                """, tenantId, java.sql.Date.valueOf(first), java.sql.Date.valueOf(last));
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String code = string(row.get("code"));
            Number count = (Number) row.get("document_count");
            if (code != null && count != null) {
                counts.put(code, count.intValue());
            }
        }
        return counts;
    }

    private static String toCsv(List<Employee> employees, YearMonth month,
                                Map<String, Integer> documents) {
        StringBuilder csv = new StringBuilder(
                "month,user_id,name,email,user_status,membership_status,roles,"
                        + "absence_documents,overtime,hours,note\n");
        for (Employee employee : employees) {
            csv.append(month).append(',').append(csv(employee.userId().toString())).append(',')
                    .append(csv(employee.name())).append(',').append(csv(employee.email())).append(',')
                    .append(csv(employee.status())).append(',')
                    .append(csv(employee.membershipStatus())).append(',')
                    .append(csv(employee.roles())).append(',')
                    .append("NICHT_ZUGEORDNET,NICHT_ERFASST,,"
                            + "keine Zeiterfassung; Belege nicht je Mitarbeiter zugeordnet\n");
        }
        csv.append("# HR-Belege im Monat: ")
                .append(documents.entrySet().stream()
                        .map(entry -> entry.getKey() + "=" + entry.getValue())
                        .reduce((left, right) -> left + ", " + right).orElse("keine"))
                .append('\n');
        return csv.toString();
    }

    private static String description(YearMonth month, List<Employee> employees,
                                      Map<String, Integer> documents,
                                      AgentArtifactStore.Artifact artifact, List<String> warnings) {
        StringBuilder text = new StringBuilder();
        text.append("Lohndaten-Vorbereitung ").append(month).append(" (keine Berechnung)\n")
                .append("Mitarbeiter: ").append(employees.size()).append('\n')
                .append("HR-Belege im Monat: ").append(documents.values().stream()
                        .mapToInt(Integer::intValue).sum()).append('\n')
                .append("Ablage: ").append(artifact.storageKey()).append("\n\n")
                .append("Hinweise:\n");
        for (String warning : warnings) {
            text.append("- ").append(warning).append('\n');
        }
        text.append("\nBitte Belege zuordnen und die Lohndaten fachlich prüfen. ")
                .append("Der Agent hat keine Beträge berechnet.");
        return text.toString();
    }

    private static Set<UUID> employeeIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Set<UUID> ids = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            try {
                ids.add(UUID.fromString(part.strip()));
            }
            catch (IllegalArgumentException ex) {
                // An invalid filter entry is ignored; the report still covers the valid ones.
            }
        }
        return ids.isEmpty() ? null : ids;
    }

    private static String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .substring(0, 16);
        }
        catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    private static String string(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String require(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing");
        }
        return value.strip();
    }
}
