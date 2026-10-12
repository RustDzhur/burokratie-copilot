package de.buerokratiecopilot.identity.web;

import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import de.buerokratiecopilot.identity.auth.SecuritySettingsService;
import de.buerokratiecopilot.identity.security.SessionAuthenticationFilter.SessionDetails;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/** The caller's own sign-in security (spec §5.2.27, §21.3). Nothing here concerns anybody else. */
@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
        type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/settings/security")
@Tag(name = "Security settings", description = "Sessions, password and second factor of the signed-in person")
public class SecuritySettingsController {

    private final SecuritySettingsService security;

    public SecuritySettingsController(SecuritySettingsService security) {
        this.security = security;
    }

    public record PasswordRequest(String currentPassword, String newPassword) {
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Second factor, password age, live sessions and the applicable rules")
    public Map<String, Object> overview() {
        return security.overview(CurrentUser.required(), currentSessionId(),
                RequestContext.tenantId().orElseThrow());
    }

    @PostMapping("/password")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Changes the password; the other sessions are ended")
    public ResponseEntity<Void> changePassword(@RequestBody PasswordRequest request,
                                               HttpServletRequest http) {
        if (request == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Es wurden keine Angaben übermittelt");
        }
        security.changePassword(CurrentUser.required(), currentSessionId(), request.currentPassword(),
                request.newPassword(), http.getRemoteAddr(), http.getHeader(HttpHeaders.USER_AGENT));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/sessions/{sessionId}/revoke")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Ends one of the caller's other sessions")
    public ResponseEntity<Void> revoke(@PathVariable UUID sessionId) {
        security.revokeSession(CurrentUser.required(), currentSessionId(), sessionId);
        return ResponseEntity.noContent().build();
    }

    private static UUID currentSessionId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getDetails() instanceof SessionDetails details
                ? details.sessionId() : null;
    }
}
