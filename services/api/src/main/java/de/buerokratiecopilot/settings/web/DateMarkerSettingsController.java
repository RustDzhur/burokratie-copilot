package de.buerokratiecopilot.settings.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.documents.dates.DateMarkerService;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * The words that explain a date, editable by the workspace (spec §9.3).
 *
 * <p><strong>Why this screen exists.</strong> The vocabulary used to be regular expressions in the code, so
 * the word an authority used and the product did not know simply did not exist — and only a deployment could
 * change that. Here a workspace adds its own words, relabels one, switches a default off, or returns
 * everything to the platform defaults. The classifier reads the same table, so an edit applies to the next
 * document.</p>
 *
 * <p><strong>The defaults are not the workspace's to change.</strong> A platform default cannot be patched or
 * deleted ({@code 409}); it belongs to every account. Switching one off is a workspace word with the same
 * kind and phrase and {@code enabled = false}, which the merge applies instead of the default. Deleting that
 * workspace word restores the default.</p>
 *
 * <p>Everything is scoped to the caller's workspace by the tenant context and the row level security policy;
 * an identifier from another workspace is not found rather than forbidden, so the answer does not even
 * confirm that the row exists.</p>
 */
@RestController
@RequestMapping("/api/v1/settings")
public class DateMarkerSettingsController {

    private final DateMarkerService markers;

    public DateMarkerSettingsController(DateMarkerService markers) {
        this.markers = markers;
    }

    /** New word. {@code enabled} defaults to true; {@code label} is optional. */
    public record CreateRequest(String kind, String phrase, String label, Boolean enabled) {
    }

    /** {@code null} leaves a field unchanged; an empty label clears it. */
    public record PatchRequest(Boolean enabled, String phrase, String label) {
    }

    private static UUID tenant() {
        return RequestContext.tenantId().orElseThrow();
    }

    @GetMapping("/date-markers")
    @PreAuthorize("hasAuthority('settings.view')")
    public Map<String, Object> list() {
        var items = markers.list(tenant()).stream().map(DateMarkerService.EffectiveMarker::asMap).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("count", items.size());
        return body;
    }

    @PostMapping("/date-markers")
    @PreAuthorize("hasAuthority('settings.manage_rules')")
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateRequest request) {
        var created = markers.create(tenant(), CurrentUser.required(), request.kind(), request.phrase(),
                request.label(), request.enabled());
        return ResponseEntity.status(HttpStatus.CREATED).body(created.asMap());
    }

    @PatchMapping("/date-markers/{id}")
    @PreAuthorize("hasAuthority('settings.manage_rules')")
    public Map<String, Object> update(@PathVariable UUID id, @RequestBody PatchRequest request) {
        return markers.update(tenant(), CurrentUser.required(), id, request.enabled(), request.phrase(),
                request.label()).asMap();
    }

    @DeleteMapping("/date-markers/{id}")
    @PreAuthorize("hasAuthority('settings.manage_rules')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        markers.delete(tenant(), CurrentUser.required(), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Removes every own word, so the defaults apply again.
     *
     * <p>The count is returned because "nothing happened" and "eight words were removed" are different
     * answers to a person who pressed a button.</p>
     */
    @PostMapping("/date-markers:reset")
    @PreAuthorize("hasAuthority('settings.manage_rules')")
    public Map<String, Object> reset() {
        return Map.of("deleted", markers.reset(tenant(), CurrentUser.required()));
    }
}
