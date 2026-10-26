package de.buerokratiecopilot.agents.crm;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JDBC access shared by the CRM agents: lookups and jsonb merges for the state the platform
 * modules keep but do not expose as service methods (contact metadata, deal metadata on cases,
 * the contact activity log, follow-up keys). Every write is a keyed jsonb merge inside the
 * tenant scope, so a repeated agent run cannot duplicate objects.
 */
@Component
public class CrmStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public CrmStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Stage and last-moved timestamp of a deal carried on a case (metadata.deal). */
    public record DealInfo(String stage, String lastMovedAt) {
    }

    public Optional<UUID> findContactByEmail(UUID tenantId, String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query("""
                select id from contacts
                where tenant_id = ? and email = ? and deleted_at is null
                order by created_at limit 1
                """, rs -> rs.next() ? Optional.of(rs.getObject("id", UUID.class)) : Optional.empty(),
                tenantId, email.strip());
    }

    public Optional<UUID> findContactByExternalKey(UUID tenantId, String externalKey) {
        if (externalKey == null || externalKey.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query("""
                select id from contacts
                where tenant_id = ? and deleted_at is null
                  and (metadata ->> 'externalKey' = ? or metadata -> 'lead' ->> 'externalKey' = ?)
                order by created_at limit 1
                """, rs -> rs.next() ? Optional.of(rs.getObject("id", UUID.class)) : Optional.empty(),
                tenantId, externalKey.strip(), externalKey.strip());
    }

    /** One contact row with the columns the CRM agents maintain, plus its parsed metadata. */
    public Optional<Map<String, Object>> findContactRow(UUID tenantId, UUID contactId) {
        return jdbc.query("""
                select id, display_name, email::text as email, phone, mobile, first_name, last_name,
                       organization_name, website, preferred_language, is_customer,
                       metadata::text as metadata
                from contacts
                where tenant_id = ? and id = ? and deleted_at is null
                """, rs -> {
            if (!rs.next()) {
                return Optional.empty();
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getObject("id", UUID.class));
            row.put("display_name", rs.getString("display_name"));
            row.put("email", rs.getString("email"));
            row.put("phone", rs.getString("phone"));
            row.put("mobile", rs.getString("mobile"));
            row.put("first_name", rs.getString("first_name"));
            row.put("last_name", rs.getString("last_name"));
            row.put("organization_name", rs.getString("organization_name"));
            row.put("website", rs.getString("website"));
            row.put("preferred_language", rs.getString("preferred_language"));
            row.put("is_customer", rs.getBoolean("is_customer"));
            row.put("metadata", parseMetadata(rs.getString("metadata")));
            return Optional.of(row);
        }, tenantId, contactId);
    }

    /** Merges a jsonb object into the contact metadata; keys of {@code merge} win. */
    public void mergeContactMetadata(UUID tenantId, UUID contactId, Map<String, Object> merge) {
        jdbc.update("""
                update contacts
                set metadata = coalesce(metadata, '{}'::jsonb) || ?::jsonb, updated_at = now()
                where tenant_id = ? and id = ? and deleted_at is null
                """, toJson(merge), tenantId, contactId);
    }

    /** Same for the case metadata, where the deal state lives. */
    public void mergeCaseMetadata(UUID tenantId, UUID caseId, Map<String, Object> merge) {
        jdbc.update("""
                update cases
                set metadata = coalesce(metadata, '{}'::jsonb) || ?::jsonb, updated_at = now()
                where tenant_id = ? and id = ? and deleted_at is null
                """, toJson(merge), tenantId, caseId);
    }

    /** Updates one allow-listed contact column; agent code never touches other columns. */
    public int setContactColumn(UUID tenantId, UUID contactId, String column, Object value) {
        String sql = switch (column) {
            case "first_name", "last_name", "organization_name", "phone", "mobile", "website",
                 "preferred_language" ->
                    "update contacts set " + column
                            + " = ?, updated_at = now() where tenant_id = ? and id = ? and deleted_at is null";
            default -> throw new IllegalArgumentException("Column not allowed for agents: " + column);
        };
        return jdbc.update(sql, value, tenantId, contactId);
    }

    public void markCustomer(UUID tenantId, UUID contactId) {
        jdbc.update("""
                update contacts set is_customer = true, updated_at = now()
                where tenant_id = ? and id = ? and deleted_at is null
                """, tenantId, contactId);
    }

    /**
     * Appends one activity to the contact's activity log unless its external key is already
     * there, which is what makes repeated runs idempotent. Returns the number of rows changed.
     */
    public int appendContactActivity(UUID tenantId, UUID contactId, Map<String, Object> activity) {
        String externalKey = activity.get("externalKey") == null ? null
                : String.valueOf(activity.get("externalKey"));
        return jdbc.update("""
                update contacts
                set metadata = jsonb_set(coalesce(metadata, '{}'::jsonb), '{activityLog}',
                                coalesce(metadata->'activityLog', '[]'::jsonb) || ?::jsonb),
                    updated_at = now()
                where tenant_id = ? and id = ? and deleted_at is null
                  and not exists (
                      select 1 from jsonb_array_elements(coalesce(metadata->'activityLog', '[]'::jsonb)) as a
                      where a->>'externalKey' is not null and a->>'externalKey' = ?)
                """, toJson(activity), tenantId, contactId, externalKey);
    }

    public boolean contactActivityExists(UUID tenantId, UUID contactId, String externalKey) {
        if (externalKey == null || externalKey.isBlank()) {
            return false;
        }
        Boolean exists = jdbc.queryForObject("""
                select exists (
                    select 1 from contacts c,
                         jsonb_array_elements(coalesce(c.metadata->'activityLog', '[]'::jsonb)) as a
                    where c.tenant_id = ? and c.id = ? and c.deleted_at is null
                      and a->>'externalKey' is not null and a->>'externalKey' = ?)
                """, Boolean.class, tenantId, contactId, externalKey);
        return Boolean.TRUE.equals(exists);
    }

    public Optional<UUID> findCaseEventByExternalKey(UUID tenantId, String externalKey) {
        if (externalKey == null || externalKey.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query("""
                select id from case_events
                where tenant_id = ? and metadata->'activity'->>'externalKey' = ?
                order by occurred_at desc limit 1
                """, rs -> rs.next() ? Optional.of(rs.getObject("id", UUID.class)) : Optional.empty(),
                tenantId, externalKey);
    }

    /** Case ids that carry deal metadata, for the pipeline scan. */
    public List<UUID> findDealCaseIds(UUID tenantId) {
        return jdbc.query("""
                select id from cases
                where tenant_id = ? and deleted_at is null and metadata->'deal' is not null
                order by created_at
                """, (rs, rowNum) -> rs.getObject("id", UUID.class), tenantId);
    }

    public Optional<DealInfo> dealInfo(UUID tenantId, UUID caseId) {
        return jdbc.query("""
                select metadata->'deal'->>'stage' as stage,
                       metadata->'deal'->>'lastMovedAt' as last_moved_at
                from cases
                where tenant_id = ? and id = ? and deleted_at is null
                """, rs -> rs.next()
                        ? Optional.of(new DealInfo(rs.getString("stage"), rs.getString("last_moved_at")))
                        : Optional.empty(),
                tenantId, caseId);
    }

    /** Newest activity recorded on the case timeline, for the after_activity follow-up rule. */
    public Optional<Instant> lastActivityAt(UUID tenantId, UUID caseId, UUID contactId) {
        return jdbc.query("""
                select max(occurred_at) from case_events
                where tenant_id = ? and case_id = ? and metadata->'activity' is not null
                  and (?::uuid is null or contact_id = ?::uuid)
                """, rs -> {
            rs.next();
            java.sql.Timestamp ts = rs.getTimestamp(1);
            return ts == null ? Optional.empty() : Optional.of(ts.toInstant());
        }, tenantId, caseId, contactId, contactId);
    }

    /** An open follow-up task planned by the agent, keyed for idempotency. */
    public Optional<UUID> findOpenFollowupTask(UUID tenantId, String followupKey) {
        return jdbc.query("""
                select id from tasks
                where tenant_id = ? and metadata->>'followupKey' = ?
                  and status not in ('DONE', 'CANCELLED') and deleted_at is null
                order by created_at limit 1
                """, rs -> rs.next() ? Optional.of(rs.getObject("id", UUID.class)) : Optional.empty(),
                tenantId, followupKey);
    }

    private Map<String, Object> parseMetadata(String text) {
        if (text == null || text.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(text, new TypeReference<Map<String, Object>>() {
            });
        }
        catch (Exception e) {
            return Map.of();
        }
    }

    private String toJson(Map<String, Object> value) {
        try {
            return mapper.writeValueAsString(value);
        }
        catch (Exception e) {
            throw new IllegalStateException("Cannot serialize agent metadata", e);
        }
    }
}
