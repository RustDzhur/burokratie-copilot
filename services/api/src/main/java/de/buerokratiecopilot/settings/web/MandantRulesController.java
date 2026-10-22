package de.buerokratiecopilot.settings.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import de.buerokratiecopilot.documents.zuordnung.MandantRoutingService;

/**
 * The rules the platform learned for assigning vouchers to clients (Kanzlei edition, §5.2).
 *
 * <p><strong>Why a person may want to see these.</strong> A rule is the reason a voucher landed in a client's
 * bookkeeping without anybody clicking. If the assignment was wrong, the rule keeps producing wrong assignments
 * for every similar letter - so the screen must show the rules, what they match, how often they fired and when,
 * and offer one button to delete one. Reading needs {@code settings.view}; deleting needs
 * {@code settings.manage_rules}, the right that guards the other business rules of the cabinet.</p>
 *
 * <p><strong>What a rule cannot do.</strong> It never changes an existing assignment (§5.2) and it never wins over
 * a person: the service refuses to overwrite a client that is already set. Deleting a rule stops future automatic
 * assignments from that signal; vouchers already assigned stay where they are, because deleting knowledge is not
 * the same as moving documents.</p>
 */
@RestController
@RequestMapping("/api/v1/settings/mandant-rules")
@Tag(name = "Einstellungen", description = "Gelernte Regeln der Mandanten-Zuordnung")
public class MandantRulesController {

    private final MandantRoutingService routing;

    public MandantRulesController(MandantRoutingService routing) {
        this.routing = routing;
    }

    /** Every learned rule of this account, with its hit count and last use. */
    @GetMapping
    @PreAuthorize("hasAuthority('settings.view')")
    @Operation(summary = "The learned client-routing rules of this account")
    public Map<String, Object> list() {
        List<Map<String, Object>> items = routing.listRules().stream()
                .map(MandantRoutingService.Rule::asMap)
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("count", items.size());
        return body;
    }

    /**
     * Deletes one rule.
     *
     * <p>An identifier of another account is answered as not found: the tenant filter and the row level
     * security policy both apply, and a refusal must not confirm that a foreign rule exists.</p>
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('settings.manage_rules')")
    @Operation(summary = "Delete a learned client-routing rule")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        routing.deleteRule(id);
        return ResponseEntity.noContent().build();
    }
}
