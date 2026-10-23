package de.buerokratiecopilot.identity.auth.web;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import de.buerokratiecopilot.identity.auth.AccountSetupService;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * The first-run wizard of §6.1: its state, the cabinet's name, and the end of it.
 *
 * <p>Only what the wizard cannot borrow from the ordinary API lives here. Creating clients is
 * {@code POST /contacts} and importing them is {@code POST /contacts/import}; uploading the first documents
 * is {@code POST /documents}. Three steps are a sequence of real actions, not a separate product.</p>
 *
 * <p>Renaming the cabinet needs {@code settings.edit}: the name belongs to the account, like every other
 * account setting. Finishing the wizard only records that the office is set up, so anybody who is in the
 * cabinet may do it - refusing a colleague would leave them in a wizard that has nothing left to do.</p>
 */
@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
        type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/setup")
@Tag(name = "Erste Einrichtung", description = "The three-step wizard of the Kanzlei edition (§6.1)")
public class AccountSetupController {

    private final AccountSetupService setup;

    public AccountSetupController(AccountSetupService setup) {
        this.setup = setup;
    }

    /** Step 1's field: the name of the office, and nothing else. */
    public record CabinetRequest(String name) {
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "How far the first-run wizard has come, and what it may change")
    public AccountSetupService.State state(Authentication authentication) {
        return setup.state(tenant(), mayEditCabinet(authentication));
    }

    @PutMapping("/cabinet")
    @PreAuthorize("hasAuthority('settings.edit')")
    @Operation(summary = "Saves the name of the office, which is the whole of step 1")
    public AccountSetupService.State renameCabinet(@RequestBody(required = false) CabinetRequest request,
                                                   Authentication authentication) {
        return setup.renameCabinet(tenant(), userId(authentication),
                request == null ? null : request.name());
    }

    @PostMapping("/complete")
    @Operation(summary = "Records that the cabinet has been set up; idempotent")
    public ResponseEntity<Void> complete(Authentication authentication) {
        setup.complete(tenant(), userId(authentication));
        return ResponseEntity.noContent().build();
    }

    /** Whether this person may rename the cabinet; the wizard shows the name read-only when they may not. */
    private static boolean mayEditCabinet(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(granted -> "settings.edit".equals(granted.getAuthority()));
    }

    private static UUID tenant() {
        // A person without a cabinet cannot run a cabinet's wizard; that is a wiring defect, not a user error.
        return RequestContext.tenantId()
                .orElseThrow(() -> new IllegalStateException("This action requires a tenant"));
    }

    private static UUID userId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Anmeldung erforderlich");
        }
        return UUID.fromString(authentication.getName());
    }
}
