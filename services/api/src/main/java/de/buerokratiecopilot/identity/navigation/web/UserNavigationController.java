package de.buerokratiecopilot.identity.navigation.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import de.buerokratiecopilot.identity.navigation.UserNavigationService;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * The signed-in person's own sidebar: which entries are shown, in which order, and which are collapsed
 * into "Weitere" (spec §23).
 *
 * <p><strong>Only one's own settings.</strong> The route is under {@code /me}, the person comes from the
 * authenticated session via {@link CurrentUser#required()}, and the account comes from
 * {@link RequestContext}. There is no identifier in the path that could name somebody else, which is the
 * simplest way to make another person's settings unreachable.</p>
 *
 * <p><strong>The default is empty, not missing.</strong> A person who never saved anything gets two empty
 * lists and {@code updatedAt = null}; the interface then applies its own default order. An error would
 * force the client to distinguish "not saved yet" from "failed", and the sidebar would be empty for
 * somebody who never opened the settings screen.</p>
 */
@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
        type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/me/navigation")
@Tag(name = "Navigation", description = "The signed-in person's own order and visibility of sidebar entries")
public class UserNavigationController {

    private final UserNavigationService navigation;

    public UserNavigationController(UserNavigationService navigation) {
        this.navigation = navigation;
    }

    /** What a client sends to replace the settings: both lists in full. */
    public record NavigationRequest(List<String> order, List<String> hidden) {
    }

    private static UUID tenant() {
        return RequestContext.tenantId().orElseThrow();
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "The own sidebar settings; empty lists when nothing was saved")
    public UserNavigationService.NavigationPreferences read() {
        return navigation.read(tenant(), CurrentUser.required());
    }

    @PutMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Replaces the own sidebar settings")
    public UserNavigationService.NavigationPreferences save(@RequestBody NavigationRequest request) {
        return navigation.save(tenant(), CurrentUser.required(), request.order(), request.hidden());
    }

    @DeleteMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Resets the own sidebar settings to the default")
    public ResponseEntity<Void> reset() {
        navigation.reset(tenant(), CurrentUser.required());
        return ResponseEntity.noContent().build();
    }
}
