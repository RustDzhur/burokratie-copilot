package de.buerokratiecopilot.agents.core;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.shared.error.ApiException;

/**
 * Executes catalogue agents and keeps the run journal. Runs synchronously in the pilot
 * (agents are rule engines on top of existing modules, not long jobs).
 */
@Service
public class AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);

    private final AgentRegistry registry;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AgentRunner(AgentRegistry registry, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.registry = registry;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public record RunResponse(UUID runId, String agentId, boolean ok, String summary,
                              List<AgentResult.Produced> produced, Map<String, String> meta) {
    }

    @Transactional
    public RunResponse run(UUID tenantId, UUID actorUserId, String agentId, String trigger,
                           Map<String, String> inputs) {
        AgentManifest manifest = registry.manifest(agentId)
                .orElseThrow(() -> ApiException.notFound("Unknown agent: " + agentId));
        Agent implementation = registry.implementation(agentId)
                .orElseThrow(() -> ApiException.conflict(
                        "Agent " + agentId + " has no implementation yet"));

        UUID runId = UUID.randomUUID();
        Instant started = Instant.now();
        Map<String, String> safeInputs = inputs == null ? Map.of() : inputs;
        jdbc.update("""
                INSERT INTO agent_runs (id, tenant_id, actor_user_id, agent_id, trigger, inputs, ok, summary, meta, started_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, false, '', '{}'::jsonb, ?)
                """, runId, tenantId, actorUserId, agentId,
                trigger == null ? "manual" : trigger, toJson(safeInputs), started);

        AgentContext context = new AgentContext(tenantId, actorUserId, trigger, started, safeInputs);
        AgentResult result;
        try {
            result = implementation.run(context);
            if (result == null) {
                result = AgentResult.failed("Agent returned nothing");
            }
        } catch (Exception e) {
            log.error("agents: run of {} failed", agentId, e);
            result = AgentResult.failed("Agent failed: " + e.getMessage());
        }

        Map<String, Object> meta = new java.util.HashMap<>(result.meta());
        meta.put("produced", result.produced().stream()
                .map(p -> Map.of("kind", p.kind(), "ref", p.ref() == null ? "" : p.ref(),
                        "label", p.label() == null ? "" : p.label()))
                .toList());

        jdbc.update("""
                UPDATE agent_runs SET ok = ?, summary = ?, meta = ?::jsonb, finished_at = ?
                WHERE id = ?
                """, result.ok(), result.summary(), toJson(meta), Instant.now(), runId);

        return new RunResponse(runId, agentId, result.ok(), result.summary(),
                result.produced(), result.meta());
    }

    public List<RunRow> recentRuns(UUID tenantId, String agentId, int limit) {
        return jdbc.query("""
                SELECT id, agent_id, trigger, inputs, ok, summary, meta, started_at, finished_at
                FROM agent_runs
                WHERE tenant_id = ? AND agent_id = ?
                ORDER BY started_at DESC
                LIMIT ?
                """, (rs, row) -> new RunRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("agent_id"),
                        rs.getString("trigger"),
                        fromJsonMap(rs.getString("inputs")),
                        rs.getBoolean("ok"),
                        rs.getString("summary"),
                        rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("finished_at") == null ? null
                                : rs.getTimestamp("finished_at").toInstant()),
                tenantId, agentId, Math.min(limit, 100));
    }

    public long runCount(String agentId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM agent_runs WHERE agent_id = ?", Long.class, agentId);
        return count == null ? 0 : count;
    }

    public record RunRow(UUID id, String agentId, String trigger, Map<String, String> inputs,
                         boolean ok, String summary, Instant startedAt, Instant finishedAt) {
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private Map<String, String> fromJsonMap(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> map = mapper.readValue(json == null ? "{}" : json, Map.class);
            return map == null ? Map.of() : map;
        } catch (Exception e) {
            return Map.of();
        }
    }
}
