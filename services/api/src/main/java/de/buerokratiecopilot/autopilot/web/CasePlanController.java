package de.buerokratiecopilot.autopilot.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.autopilot.CasePlanProposalService;
import de.buerokratiecopilot.autopilot.CasePlanService;
import de.buerokratiecopilot.autopilot.PlanAction;
import de.buerokratiecopilot.autopilot.PlanValidator.StepSpec;
import de.buerokratiecopilot.cases.CaseService;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;

/** The plan of a case (tasks CA-02, CA-03): steps with dependencies and evidence, proposed by a person or a model, validated by the platform. */
@RestController
@RequestMapping("/api/v1/cases/{caseId}/plan")
public class CasePlanController {

    private final CasePlanService plans;
    private final CasePlanProposalService proposals;
    private final CaseService cases;

    public CasePlanController(CasePlanService plans, CasePlanProposalService proposals, CaseService cases) {
        this.plans = plans;
        this.proposals = proposals;
        this.cases = cases;
    }

    public record StepRequest(String key, String action, String title, List<String> dependsOn, Boolean evidenceRequired, Map<String, Object> params) {
    }

    public record PlanRequest(List<StepRequest> steps) {
    }

    public record CompleteRequest(String evidenceType, UUID evidenceId, String note) {
    }

    public record ReasonRequest(String reason) {
    }

    private static boolean seesAll() {
        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream().anyMatch(a -> "cases.assign".equals(a.getAuthority()));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('cases.view')")
    public Map<String, Object> plan(@PathVariable UUID caseId) {
        cases.detail(caseId, CurrentUser.required(), seesAll());
        return plans.plan(caseId);
    }

    @GetMapping("/actions")
    @PreAuthorize("hasAuthority('cases.view')")
    public List<Map<String, Object>> actions() {
        return java.util.Arrays.stream(PlanAction.values()).map(a -> Map.<String, Object>of("action", a.name(), "level", a.level(),
                "auto", a.auto(), "description", a.description(), "params", a.allowedParams())).toList();
    }

    @PutMapping
    @PreAuthorize("hasAuthority('autopilot.plan')")
    public Map<String, Object> save(@PathVariable UUID caseId, @RequestBody PlanRequest request) {
        List<StepSpec> steps = request.steps() == null ? List.of() : request.steps().stream().map(s -> new StepSpec(s.key(), s.action(), s.title(),
                s.dependsOn() == null ? List.of() : s.dependsOn(), Boolean.TRUE.equals(s.evidenceRequired()), s.params())).toList();
        return plans.savePlan(caseId, steps, "USER", CurrentUser.required(), seesAll());
    }

    @PostMapping("/propose")
    @PreAuthorize("hasAuthority('autopilot.plan')")
    public Map<String, Object> propose(@PathVariable UUID caseId) {
        UUID tenantId = RequestContext.tenantId().orElseThrow();
        cases.detail(caseId, CurrentUser.required(), seesAll());
        return proposals.propose(tenantId, caseId, CurrentUser.required(), seesAll()).asMap();
    }

    @PostMapping("/advance")
    @PreAuthorize("hasAuthority('autopilot.execute')")
    public Map<String, Object> advance(@PathVariable UUID caseId) {
        int ran = plans.advance(caseId, CurrentUser.required(), seesAll());
        Map<String, Object> result = new java.util.LinkedHashMap<>(plans.plan(caseId));
        result.put("ranAutomatically", ran);
        return result;
    }

    @PostMapping("/steps/{key}/start")
    @PreAuthorize("hasAuthority('autopilot.execute')")
    public Map<String, Object> start(@PathVariable UUID caseId, @PathVariable String key) {
        return plans.startStep(caseId, key, CurrentUser.required(), seesAll());
    }

    @PostMapping("/steps/{key}/complete")
    @PreAuthorize("hasAuthority('autopilot.execute')")
    public Map<String, Object> complete(@PathVariable UUID caseId, @PathVariable String key, @RequestBody(required = false) CompleteRequest request) {
        CompleteRequest body = request == null ? new CompleteRequest(null, null, null) : request;
        return plans.completeStep(caseId, key, body.evidenceType(), body.evidenceId(), body.note(), CurrentUser.required(), seesAll());
    }

    @PostMapping("/steps/{key}/fail")
    @PreAuthorize("hasAuthority('autopilot.execute')")
    public Map<String, Object> fail(@PathVariable UUID caseId, @PathVariable String key, @RequestBody(required = false) ReasonRequest request) {
        return plans.failStep(caseId, key, request == null ? null : request.reason(), CurrentUser.required(), seesAll());
    }

    @PostMapping("/steps/{key}/skip")
    @PreAuthorize("hasAuthority('autopilot.execute')")
    public Map<String, Object> skip(@PathVariable UUID caseId, @PathVariable String key, @RequestBody ReasonRequest request) {
        return plans.skipStep(caseId, key, request.reason(), CurrentUser.required(), seesAll());
    }
}
