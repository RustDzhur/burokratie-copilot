package de.buerokratiecopilot.settings.web;

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

import de.buerokratiecopilot.settings.RuleSettingsService;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;

/** The thresholds and switches of the Radar and of document recognition: read, change with a reason, return to the default, history. */
@RestController
@RequestMapping("/api/v1/settings/rules")
public class RuleSettingsController {

    private final RuleSettingsService service;

    public RuleSettingsController(RuleSettingsService service) {
        this.service = service;
    }

    public record Change(Object value, String reason) {
    }

    public record Reset(String reason) {
    }

    private static UUID tenant() {
        return RequestContext.tenantId().orElseThrow();
    }

    @GetMapping
    @PreAuthorize("hasAuthority('settings.view')")
    public List<Map<String, Object>> list() {
        return service.list(tenant());
    }

    @PutMapping("/{key}")
    @PreAuthorize("hasAuthority('settings.manage_rules')")
    public Map<String, Object> change(@PathVariable String key, @RequestBody Change request) {
        return service.change(tenant(), CurrentUser.required(), key, request.value(), request.reason());
    }

    @PostMapping("/{key}/reset")
    @PreAuthorize("hasAuthority('settings.manage_rules')")
    public Map<String, Object> reset(@PathVariable String key, @RequestBody Reset request) {
        return service.reset(tenant(), CurrentUser.required(), key, request.reason());
    }

    @GetMapping("/{key}/history")
    @PreAuthorize("hasAuthority('settings.view')")
    public List<Map<String, Object>> history(@PathVariable String key) {
        return service.history(tenant(), key);
    }
}
