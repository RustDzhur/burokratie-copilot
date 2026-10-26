package de.buerokratiecopilot.agents.processes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.workflow.approval.ApprovalService;

/**
 * {@code approval} — Freigaben.
 *
 * <p>Creates approval requests and carries their decisions, on top of the approvals module
 * ({@link ApprovalService}): the authority matrix decides who may decide and how many people are
 * needed, the decision and its note are journaled by the module, and this agent only adds the
 * catalogue contract — idempotent request creation (an open request for the same subject is reused,
 * never duplicated) and an honest report when the matrix has no rule.</p>
 */
@Component
public class ApprovalAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(ApprovalAgent.class);

    private final ApprovalService approvals;
    private final JdbcTemplate jdbc;

    public ApprovalAgent(ApprovalService approvals, JdbcTemplate jdbc) {
        this.approvals = approvals;
        this.jdbc = jdbc;
    }

    @Override
    public String id() {
        return "approval";
    }

    @Override
    public AgentResult run(AgentContext context) {
        return RequestContext.runAs(context.tenantId(), () -> {
            String action = ApprovalInputParser.parseAction(context.input("action"));
            if (action == null) {
                return AgentResult.failed("Unbekannte Aktion '" + context.input("action")
                        + "' — erlaubt sind request, decide, status");
            }
            try {
                return switch (action) {
                    case "request" -> request(context);
                    case "decide" -> decide(context);
                    default -> status(context);
                };
            }
            catch (IllegalArgumentException e) {
                return AgentResult.failed("Ungültige Eingabe: " + e.getMessage());
            }
            catch (ApiException e) {
                return AgentResult.failed("Freigabe abgelehnt: " + e.getMessage());
            }
        });
    }

    // ------------------------------------------------------------------ request

    private AgentResult request(AgentContext context) {
        UUID tenantId = context.tenantId();
        String subjectType = context.input("subjectType");
        String subjectIdRaw = context.input("subjectId");
        if (subjectType == null || subjectType.isBlank() || subjectIdRaw == null
                || subjectIdRaw.isBlank()) {
            return AgentResult.skipped("subjectType und subjectId sind erforderlich, "
                    + "um eine Freigabe anzufragen");
        }
        subjectType = subjectType.strip().toUpperCase(java.util.Locale.ROOT);
        UUID subjectId = UUID.fromString(subjectIdRaw.trim());

        String approvalType = ApprovalInputParser.normalizeApprovalType(context.input("approvalType"));
        String title = context.input("title");
        if (title == null || title.isBlank()) {
            title = "Freigabe für " + subjectType + " " + subjectId;
        }
        String justification = context.input("justification");

        BigDecimal amount = ApprovalInputParser.parseAmount(context.input("amount"));
        if (context.input("amount") != null && !context.input("amount").isBlank() && amount == null) {
            return AgentResult.failed("amount '" + context.input("amount")
                    + "' ist keine Zahl — eine Freigabe wird nicht mit geratenem Betrag angefragt");
        }
        String direction = ApprovalInputParser.parseDirection(context.input("direction"));
        if (direction == null) {
            return AgentResult.failed("direction '" + context.input("direction")
                    + "' ist unbekannt — erlaubt sind INCOMING, OUTGOING, ANY");
        }
        Instant expiresAt = ApprovalInputParser.parseInstant(context.input("expiresAt"));
        if (context.input("expiresAt") != null && !context.input("expiresAt").isBlank()
                && expiresAt == null) {
            return AgentResult.failed("expiresAt '" + context.input("expiresAt")
                    + "' ist kein ISO-8601-Zeitpunkt");
        }

        // Idempotency: an open request for the same subject and type is reused, not duplicated.
        List<Map<String, Object>> existing = jdbc.queryForList("""
                select id::text as id, status from approvals
                where tenant_id = ? and subject_type = ? and subject_id = ? and status = 'PENDING'
                order by created_at limit 1
                """, tenantId, subjectType, subjectId);
        if (!existing.isEmpty()) {
            Map<String, String> meta = new LinkedHashMap<>(Map.of(
                    "approvalId", String.valueOf(existing.getFirst().get("id")),
                    "status", String.valueOf(existing.getFirst().get("status")),
                    "reused", "true",
                    "approvalType", approvalType));
            return AgentResult.done("Für " + subjectType + " " + subjectId
                    + " ist bereits eine Freigabe offen — sie wird nicht doppelt angefragt",
                    List.of(), meta);
        }

        ApprovalService.Request request = approvals.request(tenantId, subjectType, subjectId,
                approvalType, title, justification, amount, direction, expiresAt, context.actorUserId());
        Map<String, String> meta = new LinkedHashMap<>(Map.of(
                "approvalId", request.approvalId().toString(),
                "status", request.status(),
                "levelsRequired", String.valueOf(request.levelsRequired()),
                "levelsGiven", String.valueOf(request.levelsGiven()),
                "requiredRole", request.requiredRole() == null ? "" : request.requiredRole(),
                "requiredPermission",
                request.requiredPermission() == null ? "" : request.requiredPermission(),
                "approvalType", approvalType,
                "reused", "false"));
        String who = request.requiredRole() != null ? "Rolle " + request.requiredRole()
                : request.requiredPermission() != null ? "Berechtigung " + request.requiredPermission()
                        : "der Mitglieder";
        return AgentResult.done("Freigabe " + request.approvalId() + " angefragt: " + who
                + ", " + request.levelsRequired() + " Stufe(n)",
                List.of(new AgentResult.Produced("approval", request.approvalId().toString(),
                        "Freigabe angefragt (" + approvalType + ")")), meta);
    }

    // ------------------------------------------------------------------ decide

    private AgentResult decide(AgentContext context) {
        UUID tenantId = context.tenantId();
        String approvalIdRaw = context.input("approvalId");
        String deciderRaw = context.input("deciderUserId");
        Boolean approved = ApprovalInputParser.parseApproved(context.input("approved"));
        if (approvalIdRaw == null || approvalIdRaw.isBlank()) {
            return AgentResult.skipped("approvalId fehlt — es ist nicht klar, welche Freigabe "
                    + "entschieden werden soll");
        }
        if (approved == null) {
            return AgentResult.failed("approved muss 'true' oder 'false' sein, nicht '"
                    + context.input("approved") + "'");
        }
        if (deciderRaw == null || deciderRaw.isBlank()) {
            return AgentResult.skipped("deciderUserId fehlt — eine Freigabe braucht eine Person, "
                    + "sonst gibt niemand frei");
        }
        UUID approvalId = UUID.fromString(approvalIdRaw.trim());
        UUID deciderId = UUID.fromString(deciderRaw.trim());
        String note = context.input("note");

        ApprovalService.Decision decision = approvals.decide(tenantId, approvalId, deciderId,
                approved, note);
        Map<String, String> meta = new LinkedHashMap<>(Map.of(
                "approvalId", approvalId.toString(),
                "status", decision.status(),
                "levelsGiven", String.valueOf(decision.levelsGiven()),
                "levelsRequired", String.valueOf(decision.levelsRequired()),
                "approved", String.valueOf(approved),
                "deciderUserId", deciderId.toString()));
        return AgentResult.done("Freigabe " + approvalId + ": " + decision.status()
                + " — die Entscheidung ist im Freigabeprotokoll dokumentiert",
                List.of(new AgentResult.Produced("approval_decision", approvalId.toString(),
                        "Entschieden als " + decision.status())), meta);
    }

    // ------------------------------------------------------------------ status

    private AgentResult status(AgentContext context) {
        UUID tenantId = context.tenantId();
        String approvalIdRaw = context.input("approvalId");
        if (approvalIdRaw != null && !approvalIdRaw.isBlank()) {
            UUID approvalId = UUID.fromString(approvalIdRaw.trim());
            List<Map<String, Object>> history = approvals.history(tenantId, approvalId);
            Map<String, String> meta = new LinkedHashMap<>(Map.of(
                    "approvalId", approvalId.toString(),
                    "historyEntries", String.valueOf(history.size())));
            return AgentResult.done("Protokoll der Freigabe " + approvalId + ": "
                    + history.size() + " Eintrag/Einträge", List.of(), meta);
        }
        List<Map<String, Object>> pending = approvals.pending(tenantId);
        Map<String, String> meta = new LinkedHashMap<>(Map.of(
                "pendingApprovals", String.valueOf(pending.size())));
        return AgentResult.done(pending.size() == 0
                ? "Keine offenen Freigaben für diesen Mandanten"
                : pending.size() + " offene Freigabe(n) für diesen Mandanten", List.of(), meta);
    }
}
