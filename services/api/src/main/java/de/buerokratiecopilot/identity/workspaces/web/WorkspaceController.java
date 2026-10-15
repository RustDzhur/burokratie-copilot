package de.buerokratiecopilot.identity.workspaces.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import de.buerokratiecopilot.identity.workspaces.WorkspaceService;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/** The workspaces of the signed-in person, switching between them, and (Professional) creating client workspaces. */
@RestController
@RequestMapping("/api/v1/workspaces")
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
        type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
@Tag(name = "Workspaces", description = "Separate workspaces per company, with a switcher")
public class WorkspaceController {

    private final WorkspaceService workspaces;

    public WorkspaceController(WorkspaceService workspaces) {
        this.workspaces = workspaces;
    }

    public record CreateRequest(String name) {
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "The workspaces the signed-in person belongs to, and what they may do with them")
    public Map<String, Object> list() {
        UUID tenant = RequestContext.tenantId().orElse(null);
        UUID user = CurrentUser.required();
        return Map.of("items", workspaces.list(user, tenant), "capabilities", workspaces.capabilities(user, tenant));
    }

    @PostMapping("/{tenantId}/switch")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Continue in another workspace of the same person")
    public ResponseEntity<Void> switchTo(@PathVariable UUID tenantId, HttpServletRequest request) {
        Object sessionId = request.getAttribute("bc.sessionId");
        if (!(sessionId instanceof UUID session)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "A workspace can only be switched inside a session");
        }
        workspaces.switchTo(session, CurrentUser.required(), tenantId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Create a client workspace (owner of a Professional workspace)")
    public ResponseEntity<WorkspaceService.Workspace> create(@RequestBody CreateRequest request) {
        UUID tenant = RequestContext.tenantId().orElseThrow();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(workspaces.createClient(CurrentUser.required(), tenant, request == null ? null : request.name()));
    }
}
