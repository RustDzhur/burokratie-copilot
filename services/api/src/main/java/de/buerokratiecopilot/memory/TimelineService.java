package de.buerokratiecopilot.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The timeline of a contact, contract or case over {@code v_business_timeline}.
 *
 * <p>Each source shows only to someone who may see that module: events of cases need {@code cases.view}, of contracts
 * {@code contracts.view}, and so on. The timeline never widens what a person can already read.</p>
 */
@Service
public class TimelineService {

    public static final int MAX_PAGE = 200;

    private static final Map<String, String> SOURCE_PERMISSION = Map.of(
            "CASE", "cases.view", "CONTRACT", "contracts.view", "TASK", "tasks.view",
            "WORKFLOW", "workflows.view", "MESSAGE", "mail.view", "AUDIT", "audit.view");

    private final JdbcTemplate jdbc;

    public TimelineService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** What column of the view names the subject. */
    public enum Subject {
        CONTACT("contact_id"), CONTRACT("contract_id"), CASE("case_id");

        private final String column;

        Subject(String column) {
            this.column = column;
        }
    }

    /**
     * One page, newest first. {@code before} is the event time of the last row of the previous page; rows with the same
     * time as the cursor are not repeated because the page is cut by {@code event_time < before}, then by id.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> timeline(UUID tenantId, UUID userId, Subject subject, UUID subjectId, Instant before,
                                              int limit) {
        Set<String> allowed = allowedSources();
        if (allowed.isEmpty()) {
            return List.of();
        }
        int size = Math.max(1, Math.min(limit, MAX_PAGE));
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(subjectId);
        StringBuilder sql = new StringBuilder("select source, source_event_id, event_type, title, description, event_time, "
                + "recorded_at, actor_user_id, contact_id, contract_id, case_id, task_id, document_id "
                + "from v_business_timeline where tenant_id = ? and " + subject.column + " = ? and source in (");
        sql.append(String.join(",", allowed.stream().map(s -> "?").toList())).append(")");
        args.addAll(allowed.stream().sorted().toList());
        if (!ReaderRights.seesEveryCase()) {
            // A case is a private matter of its participants (see CaseService.accessLevel): what happens in it stays out of
            // a contact's or contract's timeline for everyone else.
            sql.append(""" 
                     and (case_id is null or exists (select 1 from cases vc where vc.id = v_business_timeline.case_id
                          and (vc.owner_user_id = ? or vc.responsible_user_id = ?
                               or exists (select 1 from case_participants p where p.case_id = vc.id and p.user_id = ?
                                          and p.revoked_at is null and (p.expires_at is null or p.expires_at > now())))))
                    """);
            args.add(userId);
            args.add(userId);
            args.add(userId);
        }
        if (before != null) {
            sql.append(" and event_time < ?");
            args.add(java.sql.Timestamp.from(before));
        }
        sql.append(" order by event_time desc, source_event_id desc limit ?");
        args.add(size);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    private static Set<String> allowedSources() {
        Set<String> held = ReaderRights.held();
        Set<String> sources = new java.util.TreeSet<>();
        SOURCE_PERMISSION.forEach((source, permission) -> {
            if (held.contains(permission)) {
                sources.add(source);
            }
        });
        return sources;
    }
}
