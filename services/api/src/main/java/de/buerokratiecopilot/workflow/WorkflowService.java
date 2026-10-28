package de.buerokratiecopilot.workflow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.agents.core.AgentRegistry;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;
import de.buerokratiecopilot.workflow.WorkflowDefinition.Definition;
import de.buerokratiecopilot.workflow.WorkflowDefinition.Validation;

/**
 * Process definitions and their versions (spec §16, F-001).
 *
 * <p><strong>A version is published once and never edited.</strong> That is what makes a run reproducible:
 * an instance that started on version three finishes on version three, whatever is published in the
 * meantime. Editing a published version would mean that a process which ran differently yesterday and
 * today gives no clue why, and the whole point of recording the version on the run is to be able to
 * answer that.</p>
 *
 * <p><strong>A definition is validated when it is published.</strong> A step that leads nowhere, an
 * unknown type, a condition without branches — all of them are faults in the definition, and finding them
 * while a customer's invoice is halfway through the process is far too late. Publishing is the moment
 * somebody looks at it.</p>
 */
@Service
public class WorkflowService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowService.class);

    /** The categories the schema allows, as far as this class uses them. */
    private static final List<String> TRIGGER_TYPES = List.of("MANUAL", "DOCUMENT", "SCHEDULE", "EVENT",
            "API");

    private final JdbcTemplate jdbc;

    /**
     * The agent catalogue is asked lazily: it builds itself by asking the Spring context for every
     * {@link de.buerokratiecopilot.agents.core.Agent} bean, and one of those agents drives the workflow
     * engine. Resolving it in the constructor would close that loop during startup; resolving it when a
     * definition is published happens long after the context exists.
     */
    private final ObjectProvider<AgentRegistry> agents;

    public WorkflowService(JdbcTemplate jdbc, ObjectProvider<AgentRegistry> agents) {
        this.jdbc = jdbc;
        this.agents = agents;
    }

    /** A workflow definition. */
    public record Workflow(UUID definitionId, String code, int currentVersion, boolean created) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("definitionId", definitionId.toString());
            map.put("code", code);
            map.put("currentVersion", currentVersion);
            map.put("created", created);
            return map;
        }
    }

    /** A published version. */
    public record Version(UUID versionId, UUID definitionId, int version, String status) {
    }

    /** Registers a process, or returns the one that already has the code. */
    @Transactional
    public Workflow define(UUID tenantId, String code, String name, String description, String category,
                          Map<String, Object> triggerConfig, UUID createdBy) {
        if (code == null || code.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Ein Prozess braucht einen Code");
        }
        String normalized = code.strip().toLowerCase(java.util.Locale.ROOT);

        List<Map<String, Object>> existing = jdbc.queryForList("""
                select id, current_version from workflow_definitions
                where tenant_id = ? and code = ?
                """, tenantId, normalized);
        if (!existing.isEmpty()) {
            // The same code is the same process; defining it again is a retry, not a second process.
            return new Workflow((UUID) existing.getFirst().get("id"), normalized,
                    ((Number) existing.getFirst().get("current_version")).intValue(), false);
        }

        String trigger = category == null ? "MANUAL" : category.toUpperCase(java.util.Locale.ROOT);
        if (!TRIGGER_TYPES.contains(trigger)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Unbekannter Auslöser: " + category);
        }

        UUID definitionId = jdbc.queryForObject("""
                insert into workflow_definitions (id, tenant_id, code, name, description, category,
                                                  trigger_config, is_active, current_version, created_by)
                values (gen_random_uuid(), ?, ?, ?::jsonb, ?::jsonb, ?, ?::jsonb, true, 0, ?)
                returning id
                """, UUID.class, tenantId, normalized, jsonOf(name == null ? normalized : name),
                jsonOf(description), trigger, toJson(triggerConfig), createdBy);

        log.info("Workflow {} defined for tenant {}", definitionId, tenantId);
        return new Workflow(definitionId, normalized, 0, true);
    }

    /**
     * Publishes a version.
     *
     * <p>Refused when the definition does not validate, and the refusal lists everything that is wrong so
     * the author fixes it in one pass.</p>
     */
    @Transactional
    public Version publish(UUID tenantId, UUID definitionId, Object definitionJson, Object inputSchema,
                           String changeNote, UUID publishedBy) {
        requireDefinition(tenantId, definitionId);
        Definition definition = WorkflowDefinition.parse(definitionJson);
        AgentRegistry catalogue = agents.getObject();
        Validation validation = WorkflowDefinition.validate(definition, agentId -> {
            if (catalogue.manifest(agentId).isEmpty()) {
                return "Der Agent '" + agentId + "' ist im Katalog unbekannt";
            }
            if (!catalogue.isImplemented(agentId)) {
                return "Der Agent '" + agentId + "' ist nur ein Konzept und noch nicht ausführbar";
            }
            return null;
        });
        if (!validation.isValid()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Der Prozess ist nicht gültig und wird nicht veröffentlicht. " + validation.summary());
        }

        int nextVersion = jdbc.queryForObject("""
                select coalesce(max(version), 0) + 1 from workflow_versions where definition_id = ?
                """, Integer.class, definitionId);

        UUID versionId = jdbc.queryForObject("""
                insert into workflow_versions (id, definition_id, tenant_id, version, status, definition,
                                               input_schema, change_note, published_by, published_at,
                                               created_by)
                values (gen_random_uuid(), ?, ?, ?, 'PUBLISHED', ?::jsonb, ?::jsonb, ?, ?, now(), ?)
                returning id
                """, UUID.class, definitionId, tenantId, nextVersion, toJson(definitionJson),
                toJson(inputSchema), changeNote, publishedBy, publishedBy);

        jdbc.update("""
                update workflow_definitions set current_version = ?, updated_at = now()
                where id = ? and tenant_id = ?
                """, nextVersion, definitionId, tenantId);

        log.info("Workflow {} version {} published by {}", definitionId, nextVersion, publishedBy);
        return new Version(versionId, definitionId, nextVersion, "PUBLISHED");
    }

    /** A draft version, which no run may use. */
    @Transactional
    public Version saveDraft(UUID tenantId, UUID definitionId, Object definitionJson, UUID createdBy) {
        requireDefinition(tenantId, definitionId);
        int nextVersion = jdbc.queryForObject("""
                select coalesce(max(version), 0) + 1 from workflow_versions where definition_id = ?
                """, Integer.class, definitionId);

        UUID versionId = jdbc.queryForObject("""
                insert into workflow_versions (id, definition_id, tenant_id, version, status, definition,
                                               input_schema, created_by)
                values (gen_random_uuid(), ?, ?, ?, 'DRAFT', ?::jsonb, '{}'::jsonb, ?)
                returning id
                """, UUID.class, definitionId, tenantId, nextVersion, toJson(definitionJson), createdBy);

        return new Version(versionId, definitionId, nextVersion, "DRAFT");
    }

    /** Switches a process off; runs that are already going continue on their version. */
    @Transactional
    public void deactivate(UUID tenantId, UUID definitionId) {
        int updated = jdbc.update("""
                update workflow_definitions set is_active = false, updated_at = now()
                where id = ? and tenant_id = ?
                """, definitionId, tenantId);
        if (updated == 0) {
            throw ApiException.notFound("workflow");
        }
    }

    /** The version a new run starts on, which is the published one the definition currently points at. */
    @Transactional(readOnly = true)
    public Version currentVersion(UUID tenantId, UUID definitionId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select v.id, v.version, v.status from workflow_definitions d
                join workflow_versions v on v.definition_id = d.id and v.version = d.current_version
                where d.id = ? and d.tenant_id = ?
                """, definitionId, tenantId);
        if (rows.isEmpty()) {
            throw ApiException.conflict("Für diesen Prozess ist keine Version veröffentlicht");
        }
        Map<String, Object> row = rows.getFirst();
        return new Version((UUID) row.get("id"), definitionId,
                ((Number) row.get("version")).intValue(), String.valueOf(row.get("status")));
    }

    /** The definition behind a version, which is what a run executes. */
    @Transactional(readOnly = true)
    public Definition definitionOf(UUID versionId) {
        List<String> definitions = jdbc.query("""
                select definition::text from workflow_versions where id = ?
                """, (rs, row) -> rs.getString("definition"), versionId);
        if (definitions.isEmpty()) {
            throw ApiException.notFound("workflow version");
        }
        return WorkflowDefinition.parse(readJson(definitions.getFirst()));
    }

    /** The versions of a process, newest first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> versions(UUID tenantId, UUID definitionId) {
        return jdbc.queryForList("""
                select version, status, change_note, published_at, created_at
                from workflow_versions where definition_id = ? and tenant_id = ?
                order by version desc
                """, definitionId, tenantId);
    }

    /** The processes of a tenant. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(UUID tenantId) {
        // The last run and the volume of the last 30 days sit next to the definition, because a process
        // list that cannot say "this one has been failing" is a list nobody can act on.
        return jdbc.queryForList("""
                select d.id, d.code, d.name->>'de' as name, d.category, d.is_active, d.current_version,
                       d.trigger_config->>'type' as trigger_type,
                       (select r.started_at from workflow_runs r where r.definition_id = d.id
                         order by r.started_at desc nulls last limit 1) as last_run_at,
                       (select r.status from workflow_runs r where r.definition_id = d.id
                         order by r.started_at desc nulls last limit 1) as last_run_status,
                       (select count(*) from workflow_runs r where r.definition_id = d.id
                          and r.started_at > now() - interval '30 days') as runs_30d
                from workflow_definitions d where d.tenant_id = ? and d.deleted_at is null and d.category <> 'CASE_PLAN' order by d.code
                """, tenantId);
    }

    private void requireDefinition(UUID tenantId, UUID definitionId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from workflow_definitions where id = ? and tenant_id = ?
                """, Integer.class, definitionId, tenantId);
        if (count == null || count == 0) {
            throw ApiException.notFound("workflow");
        }
    }

    private static String jsonOf(String value) {
        return "{\"de\":\"" + (value == null ? "" : value.replace("\\", "\\\\").replace("\"", "'")) + "\"}";
    }

    /** Turns a map into JSON; the definition arrives from a caller as a map or a string. */
    public static String toJson(Object value) {
        if (value == null) {
            return "{}";
        }
        if (value instanceof String text) {
            return text.isBlank() ? "{}" : text;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        }
        catch (Exception ex) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Der Wert konnte nicht als JSON gelesen werden: " + ex.getMessage());
        }
    }

    /** Reads JSON back into a map, for the definition of a version. */
    public static Object readJson(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        }
        catch (Exception ex) {
            throw new IllegalStateException("Die gespeicherte Definition ist nicht lesbar", ex);
        }
    }
}
