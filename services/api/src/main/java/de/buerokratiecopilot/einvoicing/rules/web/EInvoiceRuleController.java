package de.buerokratiecopilot.einvoicing.rules.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.einvoicing.rules.EInvoiceRuleRegister;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * The ruleset an account's electronic invoices are validated against (spec §23).
 *
 * <p><strong>The platform ships no rules, and these routes are how that stays true.</strong> Which rules
 * apply to an electronic invoice is a legal question that differs by specification, by version and by
 * country; the register holds what a customer published and applies it, and it does not contain a ruleset of
 * its own. That is why the ruleset version on a validated record has been empty since the table was created:
 * not because something is missing, but because <strong>an invoice validated against nothing must not be
 * reported as conforming to something</strong>.</p>
 *
 * <p><strong>Publishing is a statement by the customer, and the answer says so.</strong> A ruleset that a
 * customer publishes is their own position on which rules apply to them, recorded with who published it and
 * from what source — and the platform applies it without adopting it.</p>
 *
 * <p><strong>Retiring one is recorded rather than erased.</strong> An invoice validated against a ruleset last
 * year was validated against it; withdrawing the ruleset does not change what was true then, and a register
 * that deleted the evidence would make last year's validation unexplainable.</p>
 *
 * <p>The permissions are the catalogue's own: there is no {@code e_invoice} permission, and publishing what
 * the platform validates against is an administrative decision, so these routes use {@code settings.view} and
 * {@code settings.edit} — checked before being written.</p>
 */
@RestController
@RequestMapping("/api/v1/e-invoicing/rulesets")
@Tag(name = "E-invoicing rulesets", description = "What electronic invoices are validated against")
public class EInvoiceRuleController {

    private static final String SEE = "hasAnyAuthority('settings.view','settings.edit')";
    private static final String PUBLISH = "hasAuthority('settings.edit')";

    private final EInvoiceRuleRegister register;

    public EInvoiceRuleController(EInvoiceRuleRegister register) {
        this.register = register;
    }

    /** One rule, as the customer publishes it. */
    public record RuleBody(String code, String severity, String description, String reference,
                           Integer sortOrder) {
    }

    /** A ruleset to publish. */
    public record PublishBody(String specification, String description, String source,
                              List<RuleBody> rules) {
    }

    /**
     * Whether any ruleset exists at all.
     *
     * <p>The honest flag: until one is published, every validation runs against nothing, and that is a fact
     * an interface has to be able to show rather than leaving it to be inferred from empty fields.</p>
     */
    @GetMapping("/published")
    @PreAuthorize(SEE)
    @Operation(summary = "Whether any ruleset has been published at all")
    public Map<String, Object> anyPublished() {
        boolean published = register.anyPublished();
        return Map.of("anyPublished", published,
                // Said in words, because the consequence is not obvious from a boolean.
                "note", published
                        ? "Es ist mindestens ein Regelwerk veröffentlicht."
                        : "Es ist kein Regelwerk veröffentlicht. Bis dahin wird gegen keine Fassung geprüft, "
                                + "und eine Prüfung ist keine Aussage über Konformität.");
    }

    /** What is in force for a specification, and whether anything is. */
    @GetMapping("/in-force/{specification}")
    @PreAuthorize(SEE)
    @Operation(summary = "What is in force for a specification")
    public Map<String, Object> inForce(@PathVariable String specification) {
        EInvoiceRuleRegister.Applied applied = register.inForce(specification);
        Map<String, Object> result = new java.util.LinkedHashMap<>(applied.asMap());
        result.put("note", applied.published()
                ? "Gegen diese Fassung wird geprüft."
                : "Für diese Spezifikation ist nichts veröffentlicht; es wird gegen keine Fassung geprüft.");
        return result;
    }

    /**
     * Publishes a ruleset.
     *
     * <p>The rules come from the customer. The platform does not supply them and does not adopt them: it
     * records who published what, against which specification and from which source, and applies it.</p>
     */
    @PostMapping
    @PreAuthorize(PUBLISH)
    @Operation(summary = "Publishes a ruleset; the rules are the customer's, not the platform's")
    public ResponseEntity<Map<String, Object>> publish(@RequestBody PublishBody body) {
        if (body == null || body.specification() == null || body.specification().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Es fehlt die Spezifikation");
        }
        if (body.rules() == null || body.rules().isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Ein Regelwerk ohne Regeln sagt nichts aus und wird nicht veröffentlicht");
        }

        EInvoiceRuleRegister.Ruleset ruleset = register.publish(body.specification(), body.description(),
                body.source(), body.rules().stream()
                        .map(rule -> new EInvoiceRuleRegister.Rule(rule.code(), rule.severity(),
                                rule.description(), rule.reference(),
                                rule.sortOrder() == null ? 0 : rule.sortOrder()))
                        .toList(),
                CurrentUser.required());

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "rulesetId", ruleset.id().toString(),
                "version", ruleset.version(),
                "specification", ruleset.specification(),
                "status", ruleset.status(),
                "ruleCount", ruleset.ruleCount(),
                "note", "Veröffentlicht. Die Regeln stammen vom Kunden; die Plattform wendet sie an, ohne "
                        + "sie zu übernehmen."));
    }

    /** Withdraws a ruleset from use. What was validated against it stays explainable. */
    @PostMapping("/{rulesetId}/retire")
    @PreAuthorize(PUBLISH)
    @Operation(summary = "Withdraws a ruleset; what was validated against it stays explainable")
    public ResponseEntity<Void> retire(@PathVariable UUID rulesetId) {
        register.retire(rulesetId, CurrentUser.required());
        return ResponseEntity.noContent().build();
    }

    /** The severities a rule may carry, so an interface does not hard-code them. */
    @GetMapping("/severities")
    @PreAuthorize(SEE)
    @Operation(summary = "The severities a rule may carry")
    public List<String> severities() {
        return EInvoiceRuleRegister.SEVERITIES;
    }
}
