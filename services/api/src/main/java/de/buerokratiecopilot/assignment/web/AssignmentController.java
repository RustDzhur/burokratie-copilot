package de.buerokratiecopilot.assignment.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.assignment.AssignmentService;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * Assignment API (spec §12.2).
 *
 * <p>A suggestion endpoint that answers "nobody fits, and here is why" is more useful than one that
 * always names somebody: the user then knows to distribute the task themselves instead of trusting a
 * choice the platform could not justify.</p>
 */
@RestController
@RequestMapping("/api/v1/assignment")
@Tag(name = "Assignment", description = "Who should do this, and on what basis")
public class AssignmentController {

    private final AssignmentService assignment;

    public AssignmentController(AssignmentService assignment) {
        this.assignment = assignment;
    }

    public record SuggestionResponse(boolean found, String userId, String displayName, Double score,
                                     List<String> reasons, String explanation, String caseTypeCode) {

        static SuggestionResponse from(AssignmentService.Suggestion suggestion) {
            return new SuggestionResponse(suggestion.found(), suggestion.userId(),
                    suggestion.displayName(), suggestion.score(), suggestion.reasons(),
                    suggestion.explanation(), suggestion.caseTypeCode());
        }
    }

    @GetMapping("/tasks/{taskId}/suggestion")
    @PreAuthorize("hasAuthority('tasks.assign')")
    @Operation(summary = "Recommend somebody for a task, or say why nobody fits")
    public ResponseEntity<SuggestionResponse> suggest(@PathVariable UUID taskId) {
        return ResponseEntity.ok(SuggestionResponse.from(
                assignment.suggest(RequestContext.tenantId().orElseThrow(), taskId)));
    }

    @GetMapping("/workload")
    @PreAuthorize("hasAuthority('tasks.assign')")
    @Operation(summary = "Open tasks per person, which is the workload metric the platform observes")
    public ResponseEntity<List<Map<String, Object>>> workload() {
        return ResponseEntity.ok(assignment.workload(RequestContext.tenantId().orElseThrow()));
    }
}
