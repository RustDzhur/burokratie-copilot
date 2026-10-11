package de.buerokratiecopilot.deadlines.web;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.format.annotation.DateTimeFormat;
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

import de.buerokratiecopilot.deadlines.DeadlineService;
import de.buerokratiecopilot.deadlines.domain.DeadlineRule;
import de.buerokratiecopilot.deadlines.domain.TaskDeadline;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * Deadline and rule register API (spec §13, §20).
 *
 * <p>The register endpoints exist so that the rules can be entered and verified by whoever is
 * responsible, without a release. Every deadline response carries its type and whether it still needs
 * verification, because a date a user may rely on and one they may not must be distinguishable in the
 * interface, not only in a manual.</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Deadlines", description = "Deadline rules and the dates counted from them")
public class DeadlineController {

    private final DeadlineService deadlines;

    public DeadlineController(DeadlineService deadlines) {
        this.deadlines = deadlines;
    }

    public record RuleBody(String code, Map<String, Object> name, String description, String legalBasis,
                           Integer periodValue, String periodUnit, String countingMode,
                           String startEvent, String deliveryMethod, String holidayCalendarId) {
    }

    public record ComputeBody(String caseId, String taskId, String ruleCode, LocalDate startDate) {
    }

    public record ConfirmBody(String note) {
    }

    public record RuleResponse(String id, String code, String name, String description,
                               String legalBasis, Integer periodValue, String periodUnit,
                               String countingMode, String startEvent, String deliveryMethod,
                               boolean verified, boolean requiresVerification) {

        static RuleResponse from(DeadlineRule rule) {
            return new RuleResponse(rule.getId().toString(), rule.getCode(), rule.nameIn("de"),
                    rule.getDescription(), rule.getLegalBasis(), rule.getPeriodValue(),
                    rule.getPeriodUnit() == null ? null : rule.getPeriodUnit().name(),
                    rule.getCountingMode().name(), rule.getStartEvent().name(),
                    rule.getDeliveryMethod() == null ? null : rule.getDeliveryMethod().name(),
                    rule.isVerified(), rule.isRequiresVerification());
        }
    }

    public record DeadlineResponse(String id, String caseId, String taskId, String type, String dueDate,
                                   String source, String ruleId, boolean requiresVerification,
                                   String note, String confirmedAt) {

        static DeadlineResponse from(TaskDeadline deadline) {
            return new DeadlineResponse(deadline.getId().toString(),
                    deadline.getCaseId() == null ? null : deadline.getCaseId().toString(),
                    deadline.getTaskId() == null ? null : deadline.getTaskId().toString(),
                    deadline.getDeadlineType().name(),
                    deadline.getDueDate() == null ? null : deadline.getDueDate().toString(),
                    deadline.getSource().name(),
                    deadline.getRuleId() == null ? null : deadline.getRuleId().toString(),
                    deadline.isRequiresVerification(), deadline.getNote(),
                    deadline.getConfirmedAt() == null ? null : deadline.getConfirmedAt().toString());
        }
    }

    public record ComputedResponse(DeadlineResponse deadline, String explanation, boolean ruleVerified) {
    }

    @GetMapping("/deadline-rules")
    @PreAuthorize("hasAuthority('settings.view')")
    @Operation(summary = "The register of deadline rules, including the unverified ones")
    public ResponseEntity<List<RuleResponse>> rules() {
        return ResponseEntity.ok(deadlines
                .rulesFor(RequestContext.tenantId().orElseThrow()).stream()
                .map(RuleResponse::from).toList());
    }

    @GetMapping("/deadline-rules/pending-verification")
    @PreAuthorize("hasAuthority('settings.manage_rules')")
    @Operation(summary = "Rules somebody entered but nobody has checked yet")
    public ResponseEntity<List<RuleResponse>> pendingRules() {
        return ResponseEntity.ok(deadlines.unverifiedRules().stream().map(RuleResponse::from).toList());
    }

    @PostMapping("/deadline-rules")
    @PreAuthorize("hasAuthority('settings.manage_rules')")
    @Operation(summary = "Enter a rule; it stays unverified until somebody confirms it")
    public ResponseEntity<RuleResponse> createRule(@RequestBody RuleBody request) {
        DeadlineRule rule = deadlines.createRule(new DeadlineService.RuleRequest(
                RequestContext.tenantId().orElseThrow(), request.code(), request.name(),
                request.description(), request.legalBasis(), request.periodValue(),
                request.periodUnit() == null ? null : DeadlineRule.PeriodUnit.valueOf(request.periodUnit()),
                request.countingMode() == null ? null
                        : DeadlineRule.CountingMode.valueOf(request.countingMode()),
                request.startEvent() == null ? null
                        : DeadlineRule.StartEvent.valueOf(request.startEvent()),
                request.deliveryMethod() == null ? null
                        : DeadlineRule.DeliveryMethod.valueOf(request.deliveryMethod()),
                uuid(request.holidayCalendarId())), CurrentUser.required());

        return ResponseEntity.status(HttpStatus.CREATED).body(RuleResponse.from(rule));
    }

    @PostMapping("/deadline-rules/{id}/verify")
    @PreAuthorize("hasAuthority('settings.manage_rules')")
    @Operation(summary = "Confirm a rule against its stated source")
    public ResponseEntity<RuleResponse> verifyRule(@PathVariable UUID id) {
        return ResponseEntity.ok(RuleResponse.from(deadlines.verifyRule(id, CurrentUser.required())));
    }

    @PostMapping("/deadlines/compute")
    @PreAuthorize("hasAuthority('cases.edit')")
    @Operation(summary = "Count a deadline from a rule; an unverified rule produces an assumed date")
    public ResponseEntity<ComputedResponse> compute(@RequestBody ComputeBody request) {
        DeadlineService.Computed computed = deadlines.compute(RequestContext.tenantId().orElseThrow(),
                uuid(request.caseId()), uuid(request.taskId()), request.ruleCode(), request.startDate(),
                CurrentUser.required());

        return ResponseEntity.status(HttpStatus.CREATED).body(new ComputedResponse(
                DeadlineResponse.from(computed.deadline()), computed.explanation(),
                computed.ruleVerified()));
    }

    @PostMapping("/deadlines")
    @PreAuthorize("hasAuthority('cases.edit')")
    @Operation(summary = "Record a deadline set by a person, which carries no legal claim")
    public ResponseEntity<DeadlineResponse> recordManual(@RequestBody ComputeBody request,
                                                         @RequestParam(required = false) String note) {
        return ResponseEntity.status(HttpStatus.CREATED).body(DeadlineResponse.from(
                deadlines.recordManual(RequestContext.tenantId().orElseThrow(), uuid(request.caseId()),
                        uuid(request.taskId()), request.startDate(), note, CurrentUser.required())));
    }

    @PostMapping("/deadlines/{id}/confirm")
    @PreAuthorize("hasAuthority('cases.edit')")
    @Operation(summary = "Confirm a single date a person has checked")
    public ResponseEntity<DeadlineResponse> confirm(@PathVariable UUID id,
                                                    @RequestBody(required = false) ConfirmBody request) {
        return ResponseEntity.ok(DeadlineResponse.from(deadlines.confirm(id, CurrentUser.required(),
                request == null ? null : request.note())));
    }

    @GetMapping("/cases/{caseId}/deadlines")
    @PreAuthorize("hasAuthority('cases.view')")
    @Operation(summary = "Deadlines of a case, earliest first")
    public ResponseEntity<List<DeadlineResponse>> forCase(@PathVariable UUID caseId) {
        return ResponseEntity.ok(deadlines.forCase(caseId).stream().map(DeadlineResponse::from).toList());
    }

    @GetMapping("/tasks/{taskId}/deadlines")
    @PreAuthorize("hasAuthority('tasks.view')")
    @Operation(summary = "Deadlines of a task, earliest first")
    public ResponseEntity<List<DeadlineResponse>> forTask(@PathVariable UUID taskId) {
        return ResponseEntity.ok(deadlines.forTask(taskId).stream().map(DeadlineResponse::from).toList());
    }

    @GetMapping("/deadlines/pending-verification")
    @PreAuthorize("hasAuthority('settings.manage_rules')")
    @Operation(summary = "Dates that rest on a rule nobody has verified yet")
    public ResponseEntity<List<DeadlineResponse>> pending() {
        return ResponseEntity.ok(deadlines.awaitingVerification(RequestContext.tenantId().orElseThrow())
                .stream().map(DeadlineResponse::from).toList());
    }

    private static UUID uuid(String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }
}
