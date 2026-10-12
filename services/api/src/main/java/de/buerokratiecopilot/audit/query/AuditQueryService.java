package de.buerokratiecopilot.audit.query;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Reading the audit log (spec §30).
 *
 * <p><strong>Why this class exists at all.</strong> {@code AuditService} writes events and has no read
 * method; the only place anything read {@code audit_events} was the taxonomy service, for its own purposes.
 * An audit log that cannot be read answers nothing: the requirement is that somebody can find out who did
 * what, and until this existed the platform recorded everything and told nobody.</p>
 *
 * <p><strong>An unknown filter is refused rather than ignored.</strong> A category that does not exist,
 * silently dropped, produces a result set that looks like "nothing happened in that category" — a filter that
 * lies is worse than no filter, because a person believes they have narrowed the question.</p>
 *
 * <p><strong>The tenant comes from the context, never from an argument.</strong> These rows carry
 * {@code tenant_id} and the table is protected by a policy, so the scope is applied twice: once by the
 * policy and once by the query. Neither is decoration — the policy is what stops a mistake here from
 * becoming a leak, and the query is what makes the intent readable.</p>
 *
 * <p><strong>Nothing here writes.</strong> An audit log that could be edited from the interface it reports on
 * is not an audit log, and this service has no method that changes a row.</p>
 */
@Service
public class AuditQueryService {

    /** How many events one request may return, so that a query cannot be made to read the whole table. */
    private static final int MAX_LIMIT = 200;
    private static final int DEFAULT_LIMIT = 50;

    private final JdbcTemplate jdbc;

    public AuditQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Events for the account in context, newest first.
     *
     * <p>Every filter is applied in the query and every filter is checked first: a value the log cannot
     * contain is a mistake by the caller, not an empty result.</p>
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> events(UUID tenantId, Instant from, Instant to, String category,
                                            String result, String entityType, UUID entityId, Integer limit) {
        StringBuilder sql = new StringBuilder("""
                select id, actor_user_id, actor_type, actor_label, category, action, entity_type, entity_id,
                       entity_label, description, result, severity, correlation_id, request_path,
                       http_method, created_at
                  from audit_events
                 where tenant_id = ?
                """);
        List<Object> arguments = new ArrayList<>();
        arguments.add(tenantId);

        if (from != null) {
            sql.append(" and created_at >= ?");
            arguments.add(java.sql.Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" and created_at <= ?");
            arguments.add(java.sql.Timestamp.from(to));
        }
        if (category != null && !category.isBlank()) {
            sql.append(" and category = ?");
            arguments.add(knownCategory(category));
        }
        if (result != null && !result.isBlank()) {
            sql.append(" and result = ?");
            arguments.add(knownResult(result));
        }
        if (entityType != null && !entityType.isBlank()) {
            sql.append(" and entity_type = ?");
            arguments.add(entityType);
        }
        if (entityId != null) {
            sql.append(" and entity_id = ?");
            arguments.add(entityId);
        }
        sql.append(" order by created_at desc limit ?");
        arguments.add(clamp(limit));

        return jdbc.query(sql.toString(), (rs, row) -> {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("eventId", String.valueOf(rs.getObject("id")));
            event.put("actorUserId", rs.getObject("actor_user_id") == null
                    ? null : String.valueOf(rs.getObject("actor_user_id")));
            event.put("actorType", rs.getString("actor_type"));
            // The label as it was recorded, not as it is now: an audit entry has to say who acted then.
            event.put("actorLabel", rs.getString("actor_label"));
            event.put("category", rs.getString("category"));
            event.put("action", rs.getString("action"));
            event.put("entityType", rs.getString("entity_type"));
            event.put("entityId", rs.getObject("entity_id") == null
                    ? null : String.valueOf(rs.getObject("entity_id")));
            event.put("entityLabel", rs.getString("entity_label"));
            event.put("description", rs.getString("description"));
            event.put("result", rs.getString("result"));
            event.put("severity", rs.getString("severity"));
            event.put("correlationId", rs.getString("correlation_id"));
            // The path and method are what tie an entry to a request somebody can look up.
            event.put("requestPath", rs.getString("request_path"));
            event.put("httpMethod", rs.getString("http_method"));
            event.put("createdAt", rs.getTimestamp("created_at") == null
                    ? null : rs.getTimestamp("created_at").toInstant().toString());
            return event;
        }, arguments.toArray());
    }

    /**
     * A category the log can contain.
     *
     * <p>Refused when unknown, because a filter that is quietly ignored answers a different question from the
     * one that was asked.</p>
     */
    private static String knownCategory(String category) {
        String normalized = category.toUpperCase(java.util.Locale.ROOT);
        List<String> known = jdbcCategories();
        if (!known.contains(normalized)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Unbekannte Kategorie: " + category + ". Möglich sind " + known);
        }
        return normalized;
    }

    /** The categories the schema allows, read from the enum in Java rather than guessed. */
    private static List<String> jdbcCategories() {
        return java.util.Arrays.stream(
                        de.buerokratiecopilot.audit.domain.AuditEvent.Category.values())
                .map(Enum::name).toList();
    }

    private static String knownResult(String result) {
        String normalized = result.toUpperCase(java.util.Locale.ROOT);
        List<String> known = java.util.Arrays.stream(
                        de.buerokratiecopilot.audit.domain.AuditEvent.Result.values())
                .map(Enum::name).toList();
        if (!known.contains(normalized)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Unbekanntes Ergebnis: " + result + ". Möglich sind " + known);
        }
        return normalized;
    }

    private static int clamp(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
