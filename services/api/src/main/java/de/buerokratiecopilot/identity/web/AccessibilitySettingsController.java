package de.buerokratiecopilot.identity.web;

import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import de.buerokratiecopilot.identity.auth.UserPreferencesService;
import de.buerokratiecopilot.shared.context.CurrentUser;

/** Accessibility settings that follow the person to every device (spec §23). */
@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
        type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/settings/accessibility")
@Tag(name = "Accessibility settings", description = "Appearance and accessibility choices of the signed-in person")
public class AccessibilitySettingsController {

    private final UserPreferencesService preferences;

    public AccessibilitySettingsController(UserPreferencesService preferences) {
        this.preferences = preferences;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "The saved settings; an empty object when none were saved")
    public Map<String, Object> read() {
        return preferences.accessibility(CurrentUser.required());
    }

    @PutMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Saves the settings; an unknown key or value is refused")
    public Map<String, Object> save(@RequestBody Map<String, Object> values) {
        return preferences.saveAccessibility(CurrentUser.required(), values);
    }
}
