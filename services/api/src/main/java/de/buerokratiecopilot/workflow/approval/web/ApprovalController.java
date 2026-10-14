package de.buerokratiecopilot.workflow.approval.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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

import de.buerokratiecopilot.approvals.PaymentApprovalService;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;
import de.buerokratiecopilot.workflow.approval.ApprovalCenterService;
import de.buerokratiecopilot.workflow.approval.ApprovalService;

/**
 * Approvals and payments (spec §14, §20; §28.3 steps 7 to 9).
 *
 * <p>{@code ApprovalService} and {@code PaymentApprovalService} were implemented and tested and had no
 * endpoint, so the chain the specification requires — an invoice needs approval, an authorised person
 * gives it, a payment is prepared — could not be walked by any client. This is the way in.</p>
 *
 * <p><strong>The authority is checked by the service, not by the endpoint.</strong> Who may approve what is
 * the customer's configuration in the rule table, and an endpoint annotation cannot express it: the answer
 * depends on the amount and on the role the rule names. The endpoint therefore asks the permission to
 * <em>ask</em>, and the service decides.</p>
 *
 * <p><strong>Nobody approves their own request, and the caller cannot say who they are.</strong> The
 * decider is taken from the session; a body that named the approver would let the requester name
 * themselves.</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Approvals", description = "Decisions that need a person, and the payments they release")
public class ApprovalController {

    private static final String VIEW = "hasAnyAuthority('finance.view','finance.approve')";
    private static final String ASK = "hasAnyAuthority('finance.create','finance.approve')";
    private static final String DECIDE = "hasAnyAuthority('finance.approve','finance.edit')";

    private final ApprovalService approvals;
    private final PaymentApprovalService payments;

    private final ApprovalCenterService center;

    public ApprovalController(ApprovalService approvals, PaymentApprovalService payments, ApprovalCenterService center) {
        this.center = center;
        this.approvals = approvals;
        this.payments = payments;
    }

    /** What a caller asks an approval for. */
    public record ApprovalRequest(String subjectType, UUID subjectId, String approvalType, String title,
                                  String justification, BigDecimal amount, String direction,
                                  Instant expiresAt) {
    }

    /** A decision. Whether it counts is decided by the service, not by this body. */
    public record DecisionRequest(Boolean approved, String note, Boolean changesRequested) {
    }

    /** A payment to prepare. */
    public record PaymentRequest(UUID invoiceId, UUID caseId, BigDecimal amount, String currency,
                                 String counterpartyName, String counterpartyIban, String reference,
                                 LocalDate paymentDate) {
    }

    // ---------------------------------------------------------------- the approvals

    /**
     * Raises an approval request.
     *
     * <p>The authority the amount needs is read from the customer's rules and copied onto the request, so a
     * later change to the matrix does not move the bar for a decision somebody is already considering.</p>
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
                request.amount(), request.direction(), request.expiresAt(), actor);
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

    // ---------------------------------------------------------------- the payments

    /**
     * Prepares a payment from an approved invoice.
     *
     * <p>Refused by the service when the invoice has not been approved: a payment prepared from an
     * unapproved invoice is the step the whole approval exists to gate.</p>
     */
    @PostMapping("/payments")
    @PreAuthorize(ASK)
    @Operation(summary = "Prepares a payment; an unapproved invoice is refused")
    public ResponseEntity<Map<String, Object>> preparePayment(@RequestBody PaymentRequest request) {
        UUID tenantId = tenant();
        UUID actor = CurrentUser.required();
        if (request.amount() == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Ein Zahlungsbetrag ist erforderlich");
        }
        PaymentApprovalService.Prepared prepared = payments.prepare(new PaymentApprovalService.PrepareRequest(
                tenantId, request.invoiceId(), request.caseId(), request.amount(),
                request.currency() == null ? "EUR" : request.currency(), request.counterpartyName(),
                request.counterpartyIban(), request.reference(), request.paymentDate()), actor);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("paymentId", prepared.payment().getId().toString());
        body.put("status", prepared.payment().getStatus().name());
        body.put("approvalId", prepared.approval() == null ? null : prepared.approval().getId().toString());
        body.put("levels", prepared.levels() == null ? 0 : prepared.levels().size());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** One payment, with the approval behind it. */
    @GetMapping("/payments/{paymentId}")
    @PreAuthorize(VIEW)
    @Operation(summary = "A prepared payment")
    public Map<String, Object> payment(@PathVariable UUID paymentId) {
        UUID tenantId = tenant();
        var payment = payments.requirePayment(paymentId, tenantId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("paymentId", payment.getId().toString());
        body.put("status", payment.getStatus().name());
        body.put("amount", payment.getAmount());
        body.put("currency", payment.getCurrency());
        body.put("counterpartyName", payment.getCounterpartyName());
        return body;
    }

    /** The tenant the caller is acting in, which is never taken from a body. */
    private UUID tenant() {
        return RequestContext.tenantId().orElseThrow();
    }
}
