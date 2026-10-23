package de.buerokratiecopilot.workflow.approval.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;
import de.buerokratiecopilot.workflow.approval.ApprovalCenterService;
import de.buerokratiecopilot.workflow.approval.ApprovalService;

/**
 * Approvals (spec §14, §20; §28.3 steps 7 to 9).
 *
 * <p>An invoice or a letter that needs a decision gets a request here, and the person a request waits for
 * decides it. The payment endpoints that used to live next to these went with the payment module (§4.3).</p>
 *
 * <p><strong>The authority is checked by the service, not by the endpoint.</strong> The endpoint asks for
 * the permission to raise a request, and the service refuses a person who may not decide it.</p>
 *
 * <p><strong>Nobody approves their own request, and the caller cannot say who they are.</strong> The
 * decider is taken from the session; a body that named the approver would let the requester name
 * themselves.</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Approvals", description = "Decisions that need a person")
public class ApprovalController {

    private static final String VIEW = "hasAnyAuthority('finance.view','finance.approve')";
    private static final String ASK = "hasAnyAuthority('finance.create','finance.approve')";
    private static final String DECIDE = "hasAnyAuthority('finance.approve','finance.edit')";

    private final ApprovalService approvals;
    private final ApprovalCenterService center;

    public ApprovalController(ApprovalService approvals, ApprovalCenterService center) {
        this.center = center;
        this.approvals = approvals;
    }

    /** What a caller asks an approval for. */
    public record ApprovalRequest(String subjectType, UUID subjectId, String approvalType, String title,
                                  String justification, Instant expiresAt) {
    }

    /** A decision. Whether it counts is decided by the service, not by this body. */
    public record DecisionRequest(Boolean approved, String note, Boolean changesRequested) {
    }

    // ---------------------------------------------------------------- the approvals

    /**
     * Raises an approval request.
     *
     * <p>The person who decides is taken from the session; nobody decides their own request.</p>
     */
    @PostMapping("/approvals")
    @PreAuthorize(ASK)
    @Operation(summary = "Raises an approval request; its level comes from the configured rules")
    public ResponseEntity<Map<String, Object>> request(@RequestBody ApprovalRequest request) {
        UUID tenantId = tenant();
        UUID actor = CurrentUser.required();
        if (request.subjectId() == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Die Freigabe braucht einen Gegenstand");
        }
        ApprovalService.Request created = approvals.request(tenantId, request.subjectType(),
                request.subjectId(), request.approvalType(), request.title(), request.justification(),
                request.expiresAt(), actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(created.asMap());
    }

    /** The open requests with everything needed to decide them: case, material, warnings, term. */
    @GetMapping("/approvals/center")
    @PreAuthorize(VIEW)
    @Operation(summary = "Open approval requests with the material, the case and the warnings")
    public List<Map<String, Object>> center() {
        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        boolean all = authentication != null && authentication.getAuthorities().stream().anyMatch(a -> "cases.assign".equals(a.getAuthority()));
        return center.pending(tenant(), CurrentUser.required(), all);
    }

    /** The requests still open, which is what a person has to act on. */
    @GetMapping("/approvals")
    @PreAuthorize(VIEW)
    @Operation(summary = "The approval requests that are still open")
    public List<Map<String, Object>> pending() {
        return approvals.pending(tenant());
    }

    /**
     * Whether the caller could decide this request.
     *
     * <p>Answered before anything is attempted, so an interface can hide a button rather than show a
     * refusal — and answered by the same code that decides, so the two cannot disagree.</p>
     */
    @GetMapping("/approvals/{approvalId}/may-decide")
    @PreAuthorize(VIEW)
    @Operation(summary = "Whether the caller is entitled to decide this request")
    public Map<String, Object> mayDecide(@PathVariable UUID approvalId) {
        UUID tenantId = tenant();
        UUID actor = CurrentUser.required();
        return Map.of("mayDecide", approvals.mayDecide(tenantId, approvalId, actor));
    }

    /** Decides a request. The person is taken from the session. */
    @PostMapping("/approvals/{approvalId}/decision")
    @PreAuthorize(DECIDE)
    @Operation(summary = "Approves or rejects a request; the requester cannot decide their own")
    public Map<String, Object> decide(@PathVariable UUID approvalId, @RequestBody DecisionRequest request) {
        UUID tenantId = tenant();
        // From the session, never from the body: a body that named the approver would let the requester
        // name themselves.
        UUID actor = CurrentUser.required();
        return approvals.decide(tenantId, approvalId, actor, Boolean.TRUE.equals(request.approved()),
                request.note(), Boolean.TRUE.equals(request.changesRequested())).asMap();
    }

    /** The decisions and escalations of a request. */
    @GetMapping("/approvals/{approvalId}/history")
    @PreAuthorize(VIEW)
    @Operation(summary = "Who did what with this request, and when")
    public List<Map<String, Object>> history(@PathVariable UUID approvalId) {
        return approvals.history(tenant(), approvalId);
    }

    /** Withdraws a request that is no longer needed. */
    @PostMapping("/approvals/{approvalId}/cancel")
    @PreAuthorize(ASK)
    @Operation(summary = "Cancels a request that is no longer needed")
    public ResponseEntity<Void> cancel(@PathVariable UUID approvalId,
                                       @RequestBody(required = false) DecisionRequest request) {
        approvals.cancel(tenant(), approvalId, CurrentUser.required(),
                request == null ? null : request.note());
        return ResponseEntity.noContent().build();
    }

    /**
     * Escalates the requests nobody answered.
     *
     * <p>Silence is not agreement: an overdue request is marked expired and an escalation recorded, so it
     * ends up visible instead of blocking a process quietly.</p>
     */
    @PostMapping("/approvals/escalate-overdue")
    @PreAuthorize(DECIDE)
    @Operation(summary = "Marks the overdue requests as expired and records the escalation")
    public Map<String, Object> escalateOverdue() {
        UUID tenantId = tenant();
        CurrentUser.required();
        List<UUID> escalated = approvals.escalateOverdue(tenantId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("escalated", escalated.size());
        result.put("ids", escalated.stream().map(UUID::toString).toList());
        return result;
    }

    /** The tenant the caller is acting in, which is never taken from a body. */
    private UUID tenant() {
        return RequestContext.tenantId().orElseThrow();
    }
}
