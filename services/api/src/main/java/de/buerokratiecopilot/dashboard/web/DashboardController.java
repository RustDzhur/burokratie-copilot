package de.buerokratiecopilot.dashboard.web;

import java.util.Map;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import de.buerokratiecopilot.dashboard.DashboardService;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;

/** One call for the whole dashboard (spec §5.3, §20: {@code /dashboard}). */
@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
        type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/dashboard")
@Tag(name = "Dashboard", description = "What needs attention, aggregated for the signed-in person")
public class DashboardController {

    private final DashboardService dashboard;

    public DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/summary")
    @Operation(summary = "Counts, recent activity and system status, limited to what the caller may see")
    public Map<String, Object> summary() {
        var authorities = SecurityContextHolder.getContext().getAuthentication().getAuthorities();
        java.util.Set<String> granted = authorities.stream()
                .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                .collect(java.util.stream.Collectors.toSet());
        return dashboard.summary(new DashboardService.Viewer(RequestContext.tenantId().orElseThrow(),
                CurrentUser.required(), granted::contains, granted.contains("tasks.assign")));
    }
}
