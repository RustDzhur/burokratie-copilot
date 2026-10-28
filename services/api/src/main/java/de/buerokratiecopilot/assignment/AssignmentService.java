package de.buerokratiecopilot.assignment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.assignment.AssignmentEngine.Candidate;
import de.buerokratiecopilot.assignment.AssignmentEngine.Recommendation;
import de.buerokratiecopilot.assignment.AssignmentEngine.Rule;
import de.buerokratiecopilot.assignment.AssignmentEngine.Subject;
import de.buerokratiecopilot.shared.settings.SettingsService;
import de.buerokratiecopilot.tasks.TaskRepository;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * Recommends who should do a task, using the rules a tenant configured (spec §12.2, §31).
 *
 * <p>The rules live in the settings, not in the code: which role fits which kind of matter differs
 * between a law firm, an accounting office and a family, and a change must not need a release. The
 * expected shape is a JSON list in {@code assignment.rules} and a minimum score in
 * {@code assignment.min_score}.</p>
 *
 * <p>This service only ever <em>recommends</em>. Applying a recommendation is a separate call, and when
 * nothing reaches the minimum the task stays in the manual distribution queue. The specification is
 * explicit that work must not be assigned arbitrarily, and a service that always returns somebody is
 * exactly how that happens.</p>
 */
@Service
public class AssignmentService {

    private static final Logger log = LoggerFactory.getLogger(AssignmentService.class);

    /** Below this nobody is recommended; the task then waits for a person to distribute it. */
    private static final double DEFAULT_MINIMUM_SCORE = 0.5;

    private final JdbcTemplate jdbc;
    private final SettingsService settings;
    private final TaskRepository tasks;

    public AssignmentService(JdbcTemplate jdbc, SettingsService settings, TaskRepository tasks) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.tasks = tasks;
    }

    /** A recommendation together with the basis, ready for the API. */
    public record Suggestion(boolean found, String userId, String displayName, Double score,
                             List<String> reasons, String explanation, String caseTypeCode) {

        static Suggestion none(String explanation, String caseTypeCode) {
            return new Suggestion(false, null, null, null, List.of(), explanation, caseTypeCode);
        }
    }

    /**
     * Recommends somebody for a task.
     *
     * <p>Returns "not found" with an explanation when no candidate reaches the minimum, which is what
     * puts the task in front of a person instead of assigning it to whoever came first.</p>
     */
    @Transactional(readOnly = true)
    public Suggestion suggest(UUID tenantId, UUID taskId) {
        Task task = tasks.findByIdAndDeletedAtIsNull(taskId).orElse(null);
        String caseTypeCode = task != null && task.getCaseId() != null ? caseTypeOf(task.getCaseId()) : null;

        List<Rule> rules = rulesFor(tenantId);
        if (rules.isEmpty()) {
            return Suggestion.none("Für diesen Mandanten sind keine Zuweisungsregeln hinterlegt; "
                    + "die Aufgabe bleibt in der manuellen Verteilung.", caseTypeCode);
        }

        List<Candidate> candidates = candidatesFor(tenantId);
        if (candidates.isEmpty()) {
            return Suggestion.none("Es gibt keine verfügbaren Personen in diesem Mandanten.",
                    caseTypeCode);
        }

        double minimum = settings.getDouble("assignment.min_score", DEFAULT_MINIMUM_SCORE);
        Optional<Recommendation> recommendation = AssignmentEngine.recommend(
                new Subject(caseTypeCode, task == null ? null : task.getPriority().name()), rules,
                candidates, minimum);

        if (recommendation.isEmpty()) {
            // The acceptance criterion, stated in words the user reads: nothing was chosen because
            // nothing fitted.
            return Suggestion.none("Keine der verfügbaren Personen erreicht die Mindestbewertung von "
                    + minimum + "; die Aufgabe bleibt in der manuellen Verteilung.", caseTypeCode);
        }

        Recommendation best = recommendation.get();
        return new Suggestion(true, best.userId(), best.displayName(), best.score(), best.reasons(),
                String.join("; ", best.reasons()), caseTypeCode);
    }

    /** The workload of everybody in the tenant, for the overview a manager looks at. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> workload(UUID tenantId) {
        List<Candidate> candidates = candidatesFor(tenantId);
        int busiest = candidates.stream().mapToInt(Candidate::openTasks).max().orElse(0);

        return candidates.stream().map(candidate -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", candidate.userId());
            row.put("displayName", candidate.displayName());
            row.put("roles", candidate.roles());
            row.put("openTasks", candidate.openTasks());
            row.put("available", candidate.available());
            row.put("relativeLoad", Math.round(AssignmentEngine.relativeLoad(candidate.openTasks(),
                    busiest) * 100) / 100.0);
            return row;
        }).toList();
    }

    /** The rules of a tenant, read from the settings. */
    @Transactional(readOnly = true)
    public List<Rule> rulesFor(UUID tenantId) {
        Optional<String> configured = settings.getString("assignment.rules");
        if (configured.isEmpty() || configured.get().isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(configured.get(), new com.fasterxml.jackson.core.type.TypeReference<>() {
                    });
            List<Rule> rules = new ArrayList<>();
            for (Map<String, Object> entry : parsed) {
                rules.add(new Rule(string(entry.get("roleCode")),
                        string(entry.get("requiredPermission")), string(entry.get("caseTypeCode")),
                        number(entry.get("weight"), 0.5), string(entry.get("description")) == null
                                ? "Regel aus den Einstellungen" : string(entry.get("description"))));
            }
            return rules;
        }
        catch (Exception ex) {
            // A broken rule set is a configuration error. It is logged and the task goes to manual
            // distribution, which is the safe direction: nobody is assigned on the strength of a
            // configuration the platform could not read.
            log.warn("The assignment rules could not be read; tasks will wait for manual distribution: {}",
                    ex.getMessage());
            return List.of();
        }
    }

    /**
     * Everybody who could take work in this tenant.
     *
     * <p>Workload is counted as the open tasks a person is assigned to as a doer, which is the only
     * workload metric the platform can observe without inventing one.</p>
     */
    private List<Candidate> candidatesFor(UUID tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select u.id as user_id, u.display_name,
                       m.status as membership_status, m.access_valid_until,
                       coalesce(r.codes, '') as role_codes,
                       coalesce(p.codes, '') as permission_codes,
                       coalesce(t.open_tasks, 0) as open_tasks
                from memberships m
                join users u on u.id = m.user_id
                left join (
                    select mr.membership_id, string_agg(ro.code, ',') as codes
                    from membership_roles mr join roles ro on ro.id = mr.role_id
                    group by mr.membership_id
                ) r on r.membership_id = m.id
                left join (
                    select mr.membership_id, string_agg(distinct pe.code, ',') as codes
                    from membership_roles mr
                    join role_permissions rp on rp.role_id = mr.role_id
                    join permissions pe on pe.id = rp.permission_id
                    group by mr.membership_id
                ) p on p.membership_id = m.id
                left join (
                    select ta.user_id, count(*) as open_tasks
                    from task_assignees ta
                    join tasks t on t.id = ta.task_id
                    where ta.role = 'ASSIGNEE' and t.status not in ('DONE', 'CANCELLED')
                      and t.deleted_at is null
                    group by ta.user_id
                ) t on t.user_id = u.id
                where m.tenant_id = ? and u.status = 'ACTIVE' and u.deleted_at is null
                """, tenantId);

        List<Candidate> candidates = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            boolean available = "ACTIVE".equals(String.valueOf(row.get("membership_status")))
                    && (row.get("access_valid_until") == null
                            || ((java.sql.Timestamp) row.get("access_valid_until")).toInstant()
                                    .isAfter(Instant.now()));
            candidates.add(new Candidate(String.valueOf(row.get("user_id")),
                    row.get("display_name") == null ? "" : String.valueOf(row.get("display_name")),
                    split(String.valueOf(row.get("role_codes"))),
                    split(String.valueOf(row.get("permission_codes"))),
                    ((Number) row.get("open_tasks")).intValue(), available));
        }
        return candidates;
    }

    private String caseTypeOf(UUID caseId) {
        List<String> codes = jdbc.query("""
                select ct.code from cases c join case_types ct on ct.id = c.case_type_id where c.id = ?
                """, (rs, rowNum) -> rs.getString(1), caseId);
        return codes.isEmpty() ? null : codes.getFirst();
    }

    private static Set<String> split(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return new HashSet<>(List.of(value.split(",")));
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static double number(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? fallback : Double.parseDouble(String.valueOf(value));
        }
        catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
