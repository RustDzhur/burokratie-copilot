package de.buerokratiecopilot.mandanten.web;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import de.buerokratiecopilot.mandanten.MandantOverviewService;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * The client list of the Kanzlei and one client's card (Kanzlei edition, §5.5).
 *
 * <p>The permission is {@code contacts.view}, the same one {@code /contacts} uses: these are the same records,
 * seen through the office's vocabulary (§7: Contact → Mandant). A separate permission would have to be granted
 * to exactly the same roles and would only create the possibility that somebody may open the client list and
 * not the clients.</p>
 *
 * <p>The tenant is never a request parameter: it comes from the authenticated session, and the service also
 * filters by it in every statement. Passing a foreign client identifier therefore ends in a 404, not in
 * somebody else's client card.</p>
 */
@RestController
@RequestMapping("/api/v1/mandanten")
@Tag(name = "Mandanten", description = "The clients of the Kanzlei and their state")
public class MandantController {

    private final MandantOverviewService mandanten;

    public MandantController(MandantOverviewService mandanten) {
        this.mandanten = mandanten;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('contacts.view')")
    @Operation(summary = "The clients with their voucher counters, open deadlines and health")
    public List<MandantOverviewService.MandantSummary> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "false") boolean onlyProblems) {
        return mandanten.list(tenant(), q, onlyProblems);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('contacts.view')")
    @Operation(summary = "One client with the newest vouchers and the open deadlines")
    public MandantOverviewService.MandantDetail detail(@PathVariable UUID id) {
        return mandanten.detail(tenant(), id);
    }

    private static UUID tenant() {
        // A request reaches a controller only with a session, so a missing tenant is a wiring defect and not a
        // user error; failing loudly here is better than silently reading the wrong account.
        return RequestContext.tenantId()
                .orElseThrow(() -> new IllegalStateException("This action requires a tenant"));
    }
}
