package de.buerokratiecopilot.radar.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.radar.RadarActionService;
import de.buerokratiecopilot.radar.RadarEngine;
import de.buerokratiecopilot.radar.RadarExplanationService;
import de.buerokratiecopilot.radar.RadarFindingStatus;
import de.buerokratiecopilot.radar.RadarQueryService;
import de.buerokratiecopilot.radar.RadarRule;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;

/** The Radar: findings, the decisions a person takes on them, and the rules that raise them. */
@RestController
@RequestMapping("/api/v1/radar")
public class RadarController {

    private final RadarQueryService queries;
    private final RadarActionService actions;
    private final RadarEngine engine;
    private final RadarExplanationService explanations;

    public RadarController(RadarQueryService queries, RadarActionService actions, RadarEngine engine,
                           RadarExplanationService explanations) {
        this.explanations = explanations;
        this.queries = queries;
        this.actions = actions;
        this.engine = engine;
    }

    public record DecisionRequest(String reason, Instant until) {
    }

    public record AssignRequest(UUID userId) {
    }

    private static UUID tenant() {
        return RequestContext.tenantId().orElseThrow();
    }

    @GetMapping("/findings")
    @PreAuthorize("hasAuthority('radar.view')")
    public Map<String, Object> list(@RequestParam(required = false) List<String> status,
                                    @RequestParam(required = false) List<String> severity,
                                    @RequestParam(required = false) List<String> type,
                                    @RequestParam(required = false) UUID caseId,
                                    @RequestParam(required = false) UUID contactId,
                                    @RequestParam(required = false) UUID assignedTo,
                                    @RequestParam(required = false) UUID documentId,
                                    @RequestParam(required = false) Boolean open,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "25") int size) {
        return queries.list(tenant(), CurrentUser.required(),
                new RadarQueryService.Filter(status, severity, type, caseId, contactId, assignedTo, open, documentId), page, size);
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('radar.view')")
    public Map<String, Object> summary() {
        return queries.summary(tenant(), CurrentUser.required());
    }

    @GetMapping("/findings/{id}")
    @PreAuthorize("hasAuthority('radar.view')")
    public Map<String, Object> detail(@PathVariable UUID id) {
        return queries.detail(tenant(), CurrentUser.required(), id);
    }

    @GetMapping("/findings/{id}/evidence")
    @PreAuthorize("hasAuthority('radar.view')")
    public Object evidence(@PathVariable UUID id) {
        return queries.detail(tenant(), CurrentUser.required(), id).get("evidence");
    }

    @GetMapping("/findings/{id}/history")
    @PreAuthorize("hasAuthority('radar.view')")
    public Object history(@PathVariable UUID id) {
        return queries.detail(tenant(), CurrentUser.required(), id).get("history");
    }

    @PostMapping("/findings/{id}/review")
    @PreAuthorize("hasAuthority('radar.triage')")
    public Map<String, Object> review(@PathVariable UUID id) {
        return actions.decide(tenant(), CurrentUser.required(), id, RadarFindingStatus.UNDER_REVIEW, null, null);
    }

    @PostMapping("/findings/{id}/confirm")
    @PreAuthorize("hasAuthority('radar.triage')")
    public Map<String, Object> confirm(@PathVariable UUID id, @RequestBody(required = false) DecisionRequest request) {
        return actions.decide(tenant(), CurrentUser.required(), id, RadarFindingStatus.CONFIRMED,
                request == null ? null : request.reason(), null);
    }

    @PostMapping("/findings/{id}/dismiss")
    @PreAuthorize("hasAuthority('radar.triage')")
    public Map<String, Object> dismiss(@PathVariable UUID id, @RequestBody DecisionRequest request) {
        return actions.decide(tenant(), CurrentUser.required(), id, RadarFindingStatus.DISMISSED, request.reason(), null);
    }

    @PostMapping("/findings/{id}/resolve")
    @PreAuthorize("hasAuthority('radar.triage')")
    public Map<String, Object> resolve(@PathVariable UUID id, @RequestBody DecisionRequest request) {
        return actions.decide(tenant(), CurrentUser.required(), id, RadarFindingStatus.RESOLVED, request.reason(), null);
    }

    @PostMapping("/findings/{id}/snooze")
    @PreAuthorize("hasAuthority('radar.triage')")
    public Map<String, Object> snooze(@PathVariable UUID id, @RequestBody DecisionRequest request) {
        return actions.decide(tenant(), CurrentUser.required(), id, RadarFindingStatus.SNOOZED, request.reason(), request.until());
    }

    @PostMapping("/findings/{id}/reopen")
    @PreAuthorize("hasAuthority('radar.triage')")
    public Map<String, Object> reopen(@PathVariable UUID id, @RequestBody(required = false) DecisionRequest request) {
        return actions.decide(tenant(), CurrentUser.required(), id, RadarFindingStatus.REOPENED,
                request == null ? null : request.reason(), null);
    }

    @PostMapping("/findings/{id}/assign")
    @PreAuthorize("hasAuthority('radar.triage')")
    public Map<String, Object> assign(@PathVariable UUID id, @RequestBody AssignRequest request) {
        return actions.assign(tenant(), CurrentUser.required(), id, request.userId());
    }

    @PostMapping("/findings/{id}/create-case")
    @PreAuthorize("hasAuthority('radar.triage') and hasAuthority('cases.create')")
    public Map<String, Object> createCase(@PathVariable UUID id) {
        return actions.createCase(tenant(), CurrentUser.required(), id);
    }

    @PostMapping("/findings/{id}/re-evaluate")
    @PreAuthorize("hasAuthority('radar.triage')")
    public Map<String, Object> reEvaluate(@PathVariable UUID id) {
        return actions.reEvaluate(tenant(), CurrentUser.required(), id);
    }

    @PostMapping("/findings/{id}/explain")
    @PreAuthorize("hasAuthority('radar.triage')")
    public Map<String, Object> explain(@PathVariable UUID id) {
        queries.requireVisible(tenant(), CurrentUser.required(), id);
        return explanations.explain(tenant(), CurrentUser.required(), id).asMap();
    }

    @GetMapping("/rules")
    @PreAuthorize("hasAuthority('radar.view')")
    public List<RadarRule.Description> rules() {
        return engine.descriptions();
    }
}
