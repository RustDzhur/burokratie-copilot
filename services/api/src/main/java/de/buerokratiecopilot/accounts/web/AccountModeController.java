package de.buerokratiecopilot.accounts.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.accounts.AccountModeService;
import de.buerokratiecopilot.accounts.ModuleCatalogue;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Which modules an account has (spec §3.1, §31; H-001).
 *
 * <p>{@code AccountModeService} was implemented and tested with no endpoint, so an account's modules could
 * only be changed through the database. This is the way in.</p>
 *
 * <p><strong>The account type supplies a starting point and the account's own decision overrides it.</strong>
 * A Kanzlei that switched a module off should not get it back because its type says so. There is one kind of
 * cabinet, so the type itself is no longer switchable; only the modules are configured here.</p>
 *
 * <p>The permissions are the catalogue's own — {@code settings.view} and {@code settings.edit}. The
 * catalogue was checked first: there is no {@code accounts.manage}, and naming one would have repeated the
 * mistake that made every taxonomy endpoint answer 403.</p>
 */
@RestController
@RequestMapping("/api/v1/account")
@Tag(name = "Account modes", description = "Modules, the account type and what each implies")
public class AccountModeController {

    private static final String SEE = "hasAnyAuthority('settings.view','settings.edit')";
    private static final String CONFIGURE = "hasAuthority('settings.edit')";

    private final AccountModeService modes;

    public AccountModeController(AccountModeService modes) {
        this.modes = modes;
    }

    /** What a caller sends to switch a module on or off. */
    public record ModuleBody(boolean enabled) {
    }

    /** The account type and the modules that are actually available. */
    @GetMapping
    @PreAuthorize(SEE)
    @Operation(summary = "The account type and the modules available in it")
    public Map<String, Object> account() {
        UUID tenantId = tenant();
        String accountType = modes.accountTypeOf(tenantId);

        Map<String, Object> available = new LinkedHashMap<>();
        for (String moduleKey : moduleKeys()) {
            available.put(moduleKey, Map.of(
                    "allowed", modes.allows(tenantId, moduleKey),
                    "configured", modes.isConfigured(tenantId, moduleKey)));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accountType", accountType);
        result.put("defaultsOfType", modes.defaultsOf(accountType).stream()
                .map(ModuleCatalogue::keyOf).sorted().toList());
        result.put("modules", available);
        return result;
    }

    /**
     * Whether a module may be used.
     *
     * <p>Answered by the same code that refuses it, so a screen and an endpoint cannot disagree about what
     * somebody may see.</p>
     */
    @GetMapping("/modules/{moduleKey}")
    @PreAuthorize(SEE)
    @Operation(summary = "Whether a module may be used, and whether it was chosen or implied")
    public Map<String, Object> module(@PathVariable String moduleKey) {
        UUID tenantId = tenant();
        requireKnownModule(moduleKey);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("moduleKey", moduleKey);
        result.put("allowed", modes.allows(tenantId, moduleKey));
        result.put("configured", modes.isConfigured(tenantId, moduleKey));
        // A module that is available because the account type implies it, and one the account switched on
        // itself, are different facts: only the second survives a change of type.
        result.put("source", modes.isConfigured(tenantId, moduleKey) ? "ACCOUNT" : "ACCOUNT_TYPE");
        return result;
    }

    /**
     * Switches a module on or off for this account.
     *
     * <p>An override of the account type's defaults, which is what makes the type a starting point rather
     * than a rule.</p>
     */
    @PostMapping("/modules/{moduleKey}")
    @PreAuthorize(CONFIGURE)
    @Operation(summary = "Switches a module on or off; this overrides what the account type implies")
    public Map<String, Object> configureModule(@PathVariable String moduleKey,
                                               @RequestBody ModuleBody body) {
        UUID tenantId = tenant();
        requireKnownModule(moduleKey);
        modes.configure(tenantId, moduleKey, body.enabled(), CurrentUser.required());
        return Map.of("moduleKey", moduleKey, "allowed", modes.allows(tenantId, moduleKey),
                "configured", true);
    }

    /** Refuses a module the catalogue does not know, with the ones that exist. */
    private static void requireKnownModule(String moduleKey) {
        if (moduleKey == null || ModuleCatalogue.byKey(moduleKey) == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Unbekanntes Modul: " + moduleKey);
        }
    }

    /** The module keys the catalogue holds, which is what an interface offers. */
    private static List<String> moduleKeys() {
        return java.util.Arrays.stream(ModuleCatalogue.Module.values())
                .map(ModuleCatalogue::keyOf).sorted().toList();
    }

    /** The tenant the caller is acting in, which is never taken from a body. */
    private UUID tenant() {
        return RequestContext.tenantId().orElseThrow();
    }
}
