package de.buerokratiecopilot.agents.hr;

import java.time.Duration;
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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentInputs;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentWorkspace;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code hr-training} — Schulungsplanung.
 *
 * <p>Builds a training plan per active employee from the required trainings, role filter and the
 * due window. A training counts as completed only when a proof is supplied — a self-declaration is
 * not a Nachweis. Each open training becomes a task; the plan is stored as a document. The agent
 * books no course and sends nothing to a provider.</p>
 */
@Component
public class TrainingAgent implements Agent {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AgentWorkspace workspace;

    public TrainingAgent(JdbcTemplate jdbc, ObjectMapper mapper, AgentWorkspace workspace) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.workspace = workspace;
    }

    @Override
    public String id() {
        return "hr-training";
    }

    @Override
    public AgentResult run(AgentContext context) {
        List<String> required = AgentInputs.list(context.input("requiredTrainings"));
        if (required.isEmpty()) {
            return AgentResult.skipped("Schulungsplanung: 'requiredTrainings' fehlt — ohne "
                    + "Pflichtschulungen gibt es keinen Bedarf zu planen.");
        }
        int dueWithinDays = AgentInputs.integer(context.input("dueWithinDays"), 90, 1, 730);
        String provider = AgentInputs.blankTo(context.input("provider"), "intern");
        List<String> roleCodes = AgentInputs.list(context.input("roleCodes"));
        List<UUID> employeeIds = AgentInputs.uuidList(context.input("employeeIds"));
        List<Map<String, Object>> employees = loadEmployees(context.tenantId(), employeeIds, roleCodes);
        if (employees.isEmpty()) {
            return AgentResult.skipped("Schulungsplanung: keine aktiven Mitarbeitenden für die "
                    + "angegebenen Filter gefunden.");
        }
        Set<String> completed = completedTrainings(context.input("completedTrainings"));
        String sourceKey = "hr-training:" + AgentInputs.key(String.join(",", required),
                String.join(",", roleCodes), String.valueOf(dueWithinDays), provider);
        String html = html(required, employees, completed, dueWithinDays, provider);
        AgentWorkspace.DocumentRef document = workspace.ensureDocument(context, sourceKey, null, null,
                "Schulungsplan", html,
                Map.of("required", String.join(",", required), "employees", employees.size(),
                        "dueWithinDays", dueWithinDays), List.of());
        if (document.created()) {
            context.addProduced("generated_document", document.id().toString(), "Schulungsplan");
        }
        int planned = 0;
        int alreadyDone = 0;
        for (Map<String, Object> employee : employees) {
            UUID userId = (UUID) employee.get("id");
            String name = String.valueOf(employee.get("display_name"));
            for (String training : required) {
                if (completed.contains(userId + "|" + training.toLowerCase(Locale.GERMAN))) {
                    alreadyDone++;
                    continue;
                }
                String title = "Schulung: " + training + " — " + name;
                AgentWorkspace.TaskRef task = workspace.ensureTask(context,
                        sourceKey + ":" + userId + ":" + training.toLowerCase(Locale.GERMAN), title,
                        "Pflichtschulung: " + training
                                + "\nMitarbeitende/r: " + name + " (" + userId + ")"
                                + "\nAnbieter: " + provider
                                + "\nNachweis erforderlich; ohne Nachweis gilt die Schulung nicht "
                                + "als abgeschlossen.",
                        Task.Priority.NORMAL, context.now().plus(Duration.ofDays(dueWithinDays)),
                        List.of("hr", "training"), Map.of("employeeId", userId.toString(),
                                "training", training, "provider", provider));
                if (task.created()) {
                    planned++;
                    context.addProduced("task", task.id().toString(), title);
                }
            }
        }
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("employees", String.valueOf(employees.size()));
        meta.put("requiredTrainings", String.valueOf(required.size()));
        meta.put("planned", String.valueOf(planned));
        meta.put("alreadyCompletedWithProof", String.valueOf(alreadyDone));
        meta.put("dueWithinDays", String.valueOf(dueWithinDays));
        if (planned == 0) {
            return AgentResult.done("Schulungsplanung: keine offene Schulung — alle " + required.size()
                    + " Pflichtschulung(en) sind für die " + employees.size()
                    + " Mitarbeitenden mit Nachweis abgeschlossen.", context.produced(), meta);
        }
        return AgentResult.done("Schulungsplanung: " + planned + " Schulung(en) für "
                + employees.size() + " Mitarbeitende geplant, " + alreadyDone
                + " bereits mit Nachweis abgeschlossen.", context.produced(), meta);
    }

    private List<Map<String, Object>> loadEmployees(UUID tenantId, List<UUID> employeeIds,
                                                     List<String> roleCodes) {
        StringBuilder sql = new StringBuilder("""
                select distinct u.id, u.display_name
                from users u
                join memberships m on m.user_id = u.id
                where m.tenant_id = ? and m.status = 'ACTIVE' and u.deleted_at is null
                """);
        List<Object> params = new ArrayList<>(List.of(tenantId));
        if (!employeeIds.isEmpty()) {
            sql.append(" and u.id in (").append(String.join(",",
                    employeeIds.stream().map(id -> "?").toList())).append(")");
            params.addAll(employeeIds);
        }
        if (!roleCodes.isEmpty()) {
            sql.append(" and exists (select 1 from membership_roles mr join roles r on r.id = mr.role_id")
                    .append(" where mr.membership_id = m.id and upper(r.code) in (")
                    .append(String.join(",", roleCodes.stream().map(code -> "?").toList()))
                    .append("))");
            for (String code : roleCodes) {
                params.add(code.toUpperCase(Locale.ROOT));
            }
        }
        sql.append(" order by u.display_name");
        return jdbc.queryForList(sql.toString(), params.toArray());
    }

    /** Completed trainings are keyed by employee + training; a proof is mandatory. */
    private Set<String> completedTrainings(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<String> completed = new LinkedHashSet<>();
        try {
            List<Map<String, Object>> rows = mapper.readValue(raw,
                    new TypeReference<List<Map<String, Object>>>() {
                    });
            for (Map<String, Object> row : rows) {
                UUID employeeId = AgentInputs.uuid(String.valueOf(row.get("employeeId")));
                String training = row.get("training") == null ? null
                        : String.valueOf(row.get("training")).strip();
                Object proof = row.get("proofDocumentId") == null ? row.get("proof")
                        : row.get("proofDocumentId");
                if (employeeId != null && training != null && proof != null
                        && !String.valueOf(proof).isBlank()) {
                    completed.add(employeeId + "|" + training.toLowerCase(Locale.GERMAN));
                }
            }
        }
        catch (Exception ignored) {
            return Set.of();
        }
        return completed;
    }

    private static String html(List<String> required, List<Map<String, Object>> employees,
                               Set<String> completed, int dueWithinDays, String provider) {
        StringBuilder html = new StringBuilder("<h1>Schulungsplan</h1><p>Pflichtschulungen: ")
                .append(AgentInputs.html(String.join(", ", required)))
                .append("<br>Frist: ").append(dueWithinDays)
                .append(" Tage<br>Anbieter: ").append(AgentInputs.html(provider)).append("</p>")
                .append("<table><tr><th>Mitarbeitende/r</th><th>Offene Schulungen</th></tr>");
        for (Map<String, Object> employee : employees) {
            UUID userId = (UUID) employee.get("id");
            List<String> open = required.stream()
                    .filter(training -> !completed.contains(userId + "|"
                            + training.toLowerCase(Locale.GERMAN)))
                    .toList();
            html.append("<tr><td>").append(AgentInputs.html(String.valueOf(employee.get("display_name"))))
                    .append("</td><td>").append(open.isEmpty() ? "keine"
                            : AgentInputs.html(String.join(", ", open))).append("</td></tr>");
        }
        html.append("</table><p><em>Abgeschlossen gilt nur mit Nachweis. Der Agent bucht keine "
                + "Schulung und versendet nichts an einen Anbieter.</em></p>");
        return html.toString();
    }
}
