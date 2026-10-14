package de.buerokratiecopilot.cases.web;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.cases.CaseCriteriaService;
import de.buerokratiecopilot.cases.CaseService;
import de.buerokratiecopilot.shared.context.CurrentUser;

/** The goal of a case and its completion criteria (tasks CA-01, CA-05): what the case is for and how "finished" is checked. */
@RestController
@RequestMapping("/api/v1/cases/{caseId}")
public class CaseWorkspaceController {

    private final CaseCriteriaService criteria;
    private final CaseService cases;

    public CaseWorkspaceController(CaseCriteriaService criteria, CaseService cases) {
        this.criteria = criteria;
        this.cases = cases;
    }

    public record GoalRequest(String goal, String nextAction) {
    }

    public record CriterionRequest(String kind, String description, UUID taskId, UUID documentTypeId, UUID outboundMessageId) {
    }

    public record MetRequest(String evidenceType, UUID evidenceId, String note) {
    }

    public record WaiveRequest(String reason) {
    }

    private static boolean seesAll() {
        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream().anyMatch(a -> "cases.assign".equals(a.getAuthority()));
    }

    @GetMapping("/workspace")
    @PreAuthorize("hasAuthority('cases.view')")
    public Map<String, Object> workspace(@PathVariable UUID caseId) {
        // The same visibility as the case card: a case the person may not see does not exist for them.
        cases.detail(caseId, CurrentUser.required(), seesAll());
        return criteria.workspace(caseId);
    }

    @PutMapping("/goal")
    @PreAuthorize("hasAuthority('cases.edit')")
    public Map<String, Object> goal(@PathVariable UUID caseId, @RequestBody GoalRequest request) {
        return criteria.setGoal(caseId, request.goal(), request.nextAction(), CurrentUser.required(), seesAll());
    }

    @PostMapping("/criteria")
    @PreAuthorize("hasAuthority('cases.edit')")
    public ResponseEntity<Map<String, Object>> add(@PathVariable UUID caseId, @RequestBody CriterionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(criteria.addCriterion(caseId, request.kind(), request.description(),
                new CaseCriteriaService.Links(request.taskId(), request.documentTypeId(), request.outboundMessageId()),
                CurrentUser.required(), seesAll()));
    }

    @PostMapping("/criteria/{criterionId}/met")
    @PreAuthorize("hasAuthority('cases.edit')")
    public Map<String, Object> met(@PathVariable UUID caseId, @PathVariable UUID criterionId, @RequestBody MetRequest request) {
        return criteria.markMet(caseId, criterionId, request.evidenceType(), request.evidenceId(), request.note(),
                CurrentUser.required(), seesAll());
    }

    @PostMapping("/criteria/{criterionId}/waive")
    @PreAuthorize("hasAuthority('cases.edit')")
    public Map<String, Object> waive(@PathVariable UUID caseId, @PathVariable UUID criterionId, @RequestBody WaiveRequest request) {
        return criteria.waive(caseId, criterionId, request.reason(), CurrentUser.required(), seesAll());
    }
}
