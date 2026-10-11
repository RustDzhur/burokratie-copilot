package de.buerokratiecopilot.documents.taxonomy.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.documents.taxonomy.TaxonomyAdminService;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * Administrator interface to the taxonomy (spec §6, §20; B-008b).
 *
 * <p>The taxonomy was editable data with no way in: changing it meant SQL or a migration, so the person
 * who owns the classification had to ask the person who owns the database. These are the endpoints.</p>
 *
 * <p><strong>The permission is checked twice, deliberately.</strong> At the endpoint, so an unqualified
 * caller is refused before anything is read, and again in the service, so the rule holds for every caller
 * rather than only for the ones that come through HTTP. A check that exists in one place is a check that
 * disappears when somebody adds a second way in.</p>
 *
 * <p>Document types are platform-wide and rules are per tenant, which is the shape the schema has. The
 * endpoints therefore take the tenant from the request rather than from the body: a caller cannot claim to
 * be acting for somebody else.</p>
 */
@RestController
@RequestMapping("/api/v1/document-types")
@Tag(name = "Document types", description = "The classification vocabulary and its rules")
public class TaxonomyAdminController {

    private static final String MANAGE = "hasAnyAuthority('taxonomy.manage','documents.manage')";

    private final TaxonomyAdminService taxonomy;

    public TaxonomyAdminController(TaxonomyAdminService taxonomy) {
        this.taxonomy = taxonomy;
    }

    /** What a caller sends to create or change a type. */
    public record TypeRequest(String code, String category, String nameDe, String descriptionDe,
                              UUID parentId, Boolean requiresReview, Integer sortOrder, Boolean isActive) {
    }

    /** What a caller sends to add a rule. */
    public record RuleRequest(String ruleType, String pattern, Double weight, Boolean requiresAll) {
    }

    /** The categories and rule kinds, so an interface can offer them rather than hard-code them. */
    @GetMapping("/catalogue")
    @PreAuthorize(MANAGE)
    @Operation(summary = "The categories and rule kinds the schema allows")
    public Map<String, Object> catalogue() {
        return Map.of("categories", TaxonomyAdminService.CATEGORIES,
                "ruleTypes", TaxonomyAdminService.RULE_TYPES,
                "note", "Kategorien und Regelarten stammen aus der Datenbank, nicht aus der Oberfläche.");
    }

    @GetMapping
    @PreAuthorize(MANAGE)
    @Operation(summary = "The document types of the platform")
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "false") boolean activeOnly) {
        return taxonomy.listTypes(activeOnly);
    }

    @PostMapping
    @PreAuthorize(MANAGE)
    @Operation(summary = "Creates a document type")
    public ResponseEntity<Map<String, Object>> create(@RequestBody TypeRequest request) {
        UUID tenantId = RequestContext.tenantId().orElseThrow();
        UUID actor = CurrentUser.required();
        taxonomy.requireManage(tenantId, actor);

        TaxonomyAdminService.DocumentType created = taxonomy.createType(request.code(), request.category(),
                request.nameDe(), request.descriptionDe(), request.parentId(),
                Boolean.TRUE.equals(request.requiresReview()),
                request.sortOrder() == null ? 100 : request.sortOrder(), actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(created.asMap());
    }

    /**
     * Changes a type.
     *
     * <p>The code is not among what can be changed: documents and rules refer to it, and renaming it would
     * separate them from what they point at.</p>
     */
    @PatchMapping("/{typeId}")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Changes a document type; the code is not among the fields")
    public Map<String, Object> update(@PathVariable UUID typeId, @RequestBody TypeRequest request) {
        UUID tenantId = RequestContext.tenantId().orElseThrow();
        UUID actor = CurrentUser.required();
        taxonomy.requireManage(tenantId, actor);

        return taxonomy.updateType(typeId, request.isActive(), request.requiresReview(),
                request.sortOrder(), request.nameDe(), request.descriptionDe(), actor).asMap();
    }

    @GetMapping("/{typeId}/rules")
    @PreAuthorize(MANAGE)
    @Operation(summary = "The rules in force for a type, for this tenant and the platform")
    public List<Map<String, Object>> rules(@PathVariable UUID typeId) {
        UUID tenantId = RequestContext.tenantId().orElseThrow();
        taxonomy.requireManage(tenantId, CurrentUser.required());
        return taxonomy.rulesOf(typeId, tenantId);
    }

    /**
     * Adds a rule.
     *
     * <p>A rule whose failure would be invisible is created inactive and waits for a second person; the
     * answer says so rather than leaving the caller to notice.</p>
     */
    @PostMapping("/{typeId}/rules")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Adds a rule; a pattern rule waits for a second person")
    public ResponseEntity<Map<String, Object>> addRule(@PathVariable UUID typeId,
                                                       @RequestBody RuleRequest request) {
        UUID tenantId = RequestContext.tenantId().orElseThrow();
        UUID actor = CurrentUser.required();
        taxonomy.requireManage(tenantId, actor);

        TaxonomyAdminService.Rule rule = taxonomy.addRule(tenantId, typeId, request.ruleType(),
                request.pattern(), request.weight() == null ? 0.5 : request.weight(),
                Boolean.TRUE.equals(request.requiresAll()), actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(rule.asMap());
    }

    @PostMapping("/{typeId}/rules/{ruleId}/verify")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Confirms a rule that was waiting, and switches it on")
    public Map<String, Object> verify(@PathVariable UUID typeId, @PathVariable UUID ruleId) {
        UUID tenantId = RequestContext.tenantId().orElseThrow();
        UUID actor = CurrentUser.required();
        taxonomy.requireManage(tenantId, actor);
        return taxonomy.verifyRule(tenantId, ruleId, actor).asMap();
    }

    @PatchMapping("/{typeId}/rules/{ruleId}")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Switches a rule on or off")
    public Map<String, Object> setRule(@PathVariable UUID typeId, @PathVariable UUID ruleId,
                                       @RequestBody RuleRequest request,
                                       @RequestParam(defaultValue = "true") boolean active) {
        UUID tenantId = RequestContext.tenantId().orElseThrow();
        UUID actor = CurrentUser.required();
        taxonomy.requireManage(tenantId, actor);
        taxonomy.setRuleActive(tenantId, ruleId, active, actor);
        return Map.of("ruleId", ruleId.toString(), "active", active);
    }

    @DeleteMapping("/{typeId}/rules/{ruleId}")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Removes a rule")
    public ResponseEntity<Void> deleteRule(@PathVariable UUID typeId, @PathVariable UUID ruleId) {
        UUID tenantId = RequestContext.tenantId().orElseThrow();
        UUID actor = CurrentUser.required();
        taxonomy.requireManage(tenantId, actor);
        taxonomy.deleteRule(tenantId, ruleId, actor);
        return ResponseEntity.noContent().build();
    }

    /** The rules waiting for a second person, which is what an administrator has to act on. */
    @GetMapping("/awaiting-verification")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Rules that are waiting for a second person")
    public List<Map<String, Object>> awaitingVerification() {
        UUID tenantId = RequestContext.tenantId().orElseThrow();
        taxonomy.requireManage(tenantId, CurrentUser.required());
        return taxonomy.awaitingVerification(tenantId);
    }

    /** Who changed what, which is how a mis-filed document is traced back. */
    @GetMapping("/history")
    @PreAuthorize(MANAGE)
    @Operation(summary = "The changes made to the taxonomy")
    public List<Map<String, Object>> history(@RequestParam(defaultValue = "50") int limit) {
        UUID tenantId = RequestContext.tenantId().orElseThrow();
        taxonomy.requireManage(tenantId, CurrentUser.required());
        return taxonomy.history(tenantId, limit);
    }
}
