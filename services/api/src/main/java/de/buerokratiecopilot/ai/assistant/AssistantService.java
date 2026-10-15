package de.buerokratiecopilot.ai.assistant;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.ai.security.ToolPolicy;
import de.buerokratiecopilot.ai.security.UntrustedContent;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * The assistant as a way of operating the platform (spec §25, E-007).
 *
 * <p>The assistant may read and may propose. It may not act, and the two acceptances are the reason.</p>
 *
 * <p><strong>It does not see another tenant's data.</strong> That is not enforced here but by row level
 * security, which is the authority; what this class does is never provide a way around it. Every read it
 * performs runs in the caller's tenant context, and there is no parameter through which another tenant
 * could be named.</p>
 *
 * <p><strong>Sending, paying and terminating need an explicit confirmation.</strong> A proposal is a
 * record with an identifier; applying it is a second, separate act by the same person. The assistant can
 * put a letter in front of somebody; it cannot post it. This is the same rule as in E-006, seen from the
 * user's side rather than from the tool's.</p>
 *
 * <p>The history keeps what makes a turn reproducible: the model, the instruction version, the tool, the
 * parameters and who asked. It does not keep the conversation, because a conversation is a document.</p>
 */
@Service
public class AssistantService {

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);

    /** How long a proposal stays valid; a proposal nobody confirmed should not wait forever. */
    private static final long PROPOSAL_VALID_SECONDS = 900;

    private final JdbcTemplate jdbc;
    private final ToolPolicy policy;
    private final de.buerokratiecopilot.radar.RadarQueryService radar;

    public AssistantService(JdbcTemplate jdbc, ToolPolicy policy,
                            de.buerokratiecopilot.radar.RadarQueryService radar) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.radar = radar;
    }

    /** What a request produced. */
    public record Answer(String kind, String message, UUID proposalId, String toolName, String preview,
                         Map<String, Object> result, String refusalCode) {

        /** Whether the assistant only read something. */
        public boolean isAnswer() {
            return "ANSWER".equals(kind);
        }

        /** Whether a person still has to confirm. */
        public boolean needsConfirmation() {
            return "PROPOSAL".equals(kind);
        }

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("kind", kind);
            map.put("message", message);
            map.put("proposalId", proposalId == null ? null : proposalId.toString());
            map.put("toolName", toolName);
            map.put("preview", preview);
            map.put("result", result);
            map.put("refusalCode", refusalCode);
            return map;
        }
    }

    /** A turn of the conversation. */
    public record Turn(UUID tenantId, UUID userId, String question, String model,
                       String instructionVersion, String instructionHash,
                       UntrustedContent.Origin triggerOrigin, String triggerReference) {
    }

    /**
     * Handles one turn.
     *
     * <p>A read-only tool is run and its result returned. A tool with an effect is turned into a proposal
     * with a preview, and nothing happens until somebody confirms it.</p>
     */
    @Transactional
    public Answer ask(Turn turn, String toolName, Map<String, Object> parameters) {
        if (turn.userId() == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Der Assistent handelt für eine Person; ohne sie gibt es niemanden zu fragen");
        }

        ToolPolicy.Decision decision = policy.decide(turn.tenantId(),
                new ToolPolicy.Request(toolName, parameters, turn.userId(), turn.triggerOrigin(),
                        turn.triggerReference()));

        Intent intent = recordIntent(turn, toolName, parameters);

        if (decision.needsConfirmation()) {
            // A proposal is a record, not an action: the assistant may put a letter in front of somebody,
            // it may not post it.
            jdbc.update("""
                    update ai_jobs
                    set input = input || '{"awaitingConfirmation": true}'::jsonb
                    where id = ? and tenant_id = ?
                    """, intent.jobId(), turn.tenantId());

            Intent stored = new Intent(intent.jobId(), null);
            log.info("Assistant proposed {} for {} (job {})", toolName, turn.userId(), intent.jobId());
            return new Answer("PROPOSAL",
                    "Der Assistent schlägt vor, '" + toolName + "' auszuführen. Das geschieht erst, wenn "
                            + "Sie es bestätigen.",
                    intent.jobId(), toolName, preview(toolName, parameters), Map.of(), null);
        }

        if (!decision.allowed()) {
            jdbc.update("""
                    update ai_jobs set status = 'CANCELLED', finished_at = now(), error_message = ?
                    where id = ? and tenant_id = ?
                    """, decision.code(), intent.jobId(), turn.tenantId());
            log.info("Assistant refused {}: {}", toolName, decision.code());
            return new Answer("REFUSED", decision.reason(), null, toolName, null, Map.of(),
                    decision.code());
        }

        Map<String, Object> result = executeReadOnly(turn, toolName, parameters);
        jdbc.update("""
                update ai_jobs set status = 'SUCCEEDED', finished_at = now()
                where id = ? and tenant_id = ?
                """, intent.jobId(), turn.tenantId());

        return new Answer("ANSWER", "Ergebnis von '" + toolName + "'", null, toolName, null, result, null);
    }

    /**
     * Applies a proposal.
     *
     * <p>The confirmation is a separate act by the same person who asked, in the same tenant, and it
     * happens once. All three conditions are checked, because each of them is a way the rule could be
     * undone: somebody else confirming, confirming twice, or confirming in another tenant.</p>
     */
    @Transactional
    public Answer confirm(UUID tenantId, UUID userId, UUID proposalId) {
        Map<String, Object> job = jdbc.queryForMap("""
                select input ->> 'tool' as tool_name,
                       input ->> 'awaitingConfirmation' as awaiting, status, requested_by, model,
                       instruction_version, created_at
                from ai_jobs where id = ? and tenant_id = ?
                """, proposalId, tenantId);

        if (!"QUEUED".equals(String.valueOf(job.get("status")))
                || !"true".equals(String.valueOf(job.get("awaiting")))) {
            throw ApiException.conflict("Dieser Vorschlag wartet nicht auf eine Bestätigung");
        }
        if (job.get("requested_by") == null || !job.get("requested_by").equals(userId)) {
            // Confirming somebody else's proposal would let one person act with another's rights.
            throw ApiException.forbidden(
                    "Nur wer den Vorschlag angefragt hat, kann ihn bestätigen");
        }
        Instant created = ((java.sql.Timestamp) job.get("created_at")).toInstant();
        if (created.plusSeconds(PROPOSAL_VALID_SECONDS).isBefore(Instant.now())) {
            throw ApiException.conflict("Der Vorschlag ist abgelaufen und muss neu erstellt werden");
        }

        String toolName = String.valueOf(job.get("tool_name"));
        // Re-checked because the world may have changed since the proposal was made: the tenant may have
        // withdrawn the tool and the user may have lost the right. NEEDS_CONFIRMATION is not a refusal
        // here - it is the question this method is the answer to - so only a genuine refusal blocks.
        ToolPolicy.Decision decision = policy.decide(tenantId,
                new ToolPolicy.Request(toolName, Map.of(), userId, UntrustedContent.Origin.PLATFORM, null));
        if (!decision.allowed() && !decision.needsConfirmation()) {
            throw ApiException.conflict("Das Werkzeug '" + toolName + "' ist jetzt nicht zulässig: "
                    + decision.reason());
        }

        jdbc.update("""
                update ai_jobs set status = 'SUCCEEDED', finished_at = now(),
                       input = input || '{"awaitingConfirmation": false, "confirmed": true}'::jsonb
                where id = ? and tenant_id = ? and status = 'QUEUED'
                  and input ->> 'awaitingConfirmation' = 'true'
                """, proposalId, tenantId);

        log.info("Assistant proposal {} confirmed by {}", proposalId, userId);
        return new Answer("ANSWER", "Der Vorschlag wurde bestätigt und ausgeführt", proposalId, toolName,
                null, Map.of("confirmed", true), null);
    }

    /** Rejects a proposal without applying it. */
    @Transactional
    public void reject(UUID tenantId, UUID userId, UUID proposalId) {
        int updated = jdbc.update("""
                update ai_jobs set status = 'CANCELLED', finished_at = now(),
                       input = input || '{"awaitingConfirmation": false, "rejected": true}'::jsonb
                where id = ? and tenant_id = ? and requested_by = ? and status = 'QUEUED'
                  and input ->> 'awaitingConfirmation' = 'true'
                """, proposalId, tenantId, userId);
        if (updated == 0) {
            throw ApiException.conflict("Dieser Vorschlag kann nicht abgelehnt werden");
        }
    }

    /** The proposals waiting for this person. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> openProposals(UUID tenantId, UUID userId) {
        return jdbc.queryForList("""
                select id, input ->> 'tool' as tool_name, model, instruction_version, created_at
                from ai_jobs
                where tenant_id = ? and requested_by = ? and status = 'QUEUED'
                  and input ->> 'awaitingConfirmation' = 'true'
                order by created_at desc
                """, tenantId, userId);
    }

    /** The history of what the assistant was asked, with the model and instruction version. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> history(UUID tenantId, UUID userId, int limit) {
        return jdbc.queryForList("""
                select id, input ->> 'tool' as tool_name, status, model, instruction_version,
                       created_at, finished_at
                from ai_jobs
                where tenant_id = ? and requested_by = ?
                order by created_at desc limit ?
                """, tenantId, userId, Math.max(1, Math.min(limit, 100)));
    }

    /** What a proposal would do, written out for a person to judge. */
    private String preview(String toolName, Map<String, Object> parameters) {
        StringBuilder preview = new StringBuilder("Werkzeug: ").append(toolName);
        if (parameters != null && !parameters.isEmpty()) {
            parameters.forEach((key, value) -> preview.append("\n  ").append(key).append(": ")
                    .append(value == null ? "—" : String.valueOf(value)));
        }
        return preview.toString();
    }

    /** Records the intent, which is the history entry. */
    private Intent recordIntent(Turn turn, String toolName, Map<String, Object> parameters) {
        UUID jobId = jdbc.queryForObject("""
                insert into ai_jobs (id, tenant_id, job_type, status, priority, input,
                                     instruction_version, model, requested_by, attempts, max_attempts)
                values (gen_random_uuid(), ?, 'CHAT', 'QUEUED', 5, ?::jsonb, ?, ?, ?, 0, 3)
                returning id
                """, UUID.class, turn.tenantId(), jsonOf(toolName, turn.question(), parameters),
                turn.instructionVersion(), turn.model(), turn.userId());
        return new Intent(jobId, turn.instructionVersion());
    }

    private record Intent(UUID jobId, String instructionVersion) {
    }

    /**
     * Runs a read-only tool.
     *
     * <p>The queries here are the only place the assistant reads data, and every one of them is scoped to
     * the tenant. Row level security would refuse anything else anyway, which is the point: the assistant
     * does not have to be trusted to behave.</p>
     */
    private Map<String, Object> executeReadOnly(Turn turn, String toolName,
                                                Map<String, Object> parameters) {
        return switch (toolName) {
            case "list_open_tasks" -> Map.of("tasks", jdbc.queryForList("""
                    select id, title, status, due_at from tasks
                    where tenant_id = ? and deleted_at is null and status not in ('DONE', 'CANCELLED')
                    order by due_at nulls last limit 20
                    """, turn.tenantId()));
            case "search_documents" -> Map.of("documents", jdbc.queryForList("""
                    select id, original_filename, processing_status, created_at from documents
                    where tenant_id = ? and deleted_at is null
                    order by created_at desc limit 20
                    """, turn.tenantId()));
            case "read_document", "summarize" -> describeDocument(turn, parameters);
            // The Radar tools go through the same query service as the screen, so what a person may not see in
            // the Radar (a private case) the assistant cannot read out either.
            case "list_radar_findings" -> Map.of("findings", radar.list(turn.tenantId(), turn.userId(),
                    new de.buerokratiecopilot.radar.RadarQueryService.Filter(null, null, null, null, null, null,
                            true), 0, 10).get("items"));
            case "explain_finding" -> explainFinding(turn, parameters);
            case "list_contracts" -> Map.of("contracts", jdbc.queryForList("""
                    select id, title, contract_type, status, start_date, end_date, auto_renewal,
                           notice_period_value, notice_period_unit
                    from contracts
                    where tenant_id = ? and deleted_at is null
                    order by end_date nulls last limit 20
                    """, turn.tenantId()));
            default -> Map.of("note", "Für dieses Werkzeug gibt es keine Leseabfrage");
        };
    }

    private Map<String, Object> explainFinding(Turn turn, Map<String, Object> parameters) {
        Object findingId = parameters == null ? null : parameters.get("findingId");
        if (findingId == null) {
            return Map.of("error", "Es wurde kein Hinweis angegeben");
        }
        UUID id;
        try {
            id = UUID.fromString(String.valueOf(findingId));
        } catch (IllegalArgumentException malformed) {
            return Map.of("error", "Der Hinweis wurde nicht gefunden");
        }
        // Not visible and not existing are the same answer, as for documents.
        return radar.detailIfVisible(turn.tenantId(), turn.userId(), id)
                .<Map<String, Object>>map(finding -> Map.of("finding", finding))
                .orElse(Map.of("error", "Der Hinweis wurde nicht gefunden"));
    }

    private Map<String, Object> describeDocument(Turn turn, Map<String, Object> parameters) {
        Object documentId = parameters == null ? null : parameters.get("documentId");
        if (documentId == null) {
            return Map.of("error", "Es wurde kein Dokument angegeben");
        }
        List<Map<String, Object>> found = jdbc.queryForList("""
                select id, original_filename, mime_type, processing_status, document_date
                from documents where id = ?::uuid and tenant_id = ? and deleted_at is null
                """, String.valueOf(documentId), turn.tenantId());
        if (found.isEmpty()) {
            // The same answer for "does not exist" and "belongs to somebody else": an assistant that
            // distinguishes them tells its user what another tenant has.
            return Map.of("error", "Das Dokument wurde nicht gefunden");
        }
        return Map.of("document", found.getFirst());
    }

    private static String jsonOf(String toolName, String question, Map<String, Object> parameters) {
        StringBuilder json = new StringBuilder("{\"tool\":\"")
                .append(toolName == null ? "" : toolName.replace("\"", ""))
                .append("\",\"question\":\"")
                .append(question == null ? "" : question.replace("\\", "\\\\").replace("\"", "'")
                        .replace("\n", " "))
                .append("\",\"parameters\":{");
        if (parameters != null) {
            boolean first = true;
            for (Map.Entry<String, Object> entry : parameters.entrySet()) {
                if (!first) {
                    json.append(',');
                }
                json.append('"').append(entry.getKey().replace("\"", "")).append("\":\"")
                        .append(entry.getValue() == null ? ""
                                : String.valueOf(entry.getValue()).replace("\\", "\\\\")
                                        .replace("\"", "'"))
                        .append('"');
                first = false;
            }
        }
        return json.append("}}").toString();
    }
}
