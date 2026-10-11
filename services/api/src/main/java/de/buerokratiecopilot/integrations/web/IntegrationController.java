package de.buerokratiecopilot.integrations.web;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.integrations.IntegrationService;
import de.buerokratiecopilot.integrations.adapters.IntegrationAdapterCatalogue;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Integrations (spec §18, §20).
 *
 * <p>{@code IntegrationService} and the adapter catalogue were implemented and tested with no endpoint, so
 * an integration could be described and never configured. This is the way in.</p>
 *
 * <p><strong>The catalogue tells the truth about what exists.</strong> Each entry says whether it is
 * implemented in this repository, and the ones that are not answer {@code NOT_SUPPORTED} rather than
 * looking merely unconfigured: "not set up yet" and "does not exist" lead a person to do completely
 * different things, and an endpoint that blurred them would undo the distinction the catalogue makes.</p>
 *
 * <p><strong>Credentials are never returned.</strong> What comes back is whether any were stored, which is
 * the question an interface actually has — "is this connected" — and not the secret.</p>
 *
 * <p>The permissions are the catalogue's own — {@code integrations.view}, {@code integrations.configure},
 * {@code integrations.connect}, {@code integrations.disconnect}.</p>
 */
@RestController
@RequestMapping("/api/v1/integrations")
@Tag(name = "Integrations", description = "Connections, their status and what they need")
public class IntegrationController {

    private static final String SEE = "hasAnyAuthority('integrations.view','integrations.configure')";
    private static final String CONFIGURE = "hasAnyAuthority('integrations.configure','integrations.connect')";

    private final IntegrationService integrations;
    private final IntegrationAdapterCatalogue catalogue;

    public IntegrationController(IntegrationService integrations, IntegrationAdapterCatalogue catalogue) {
        this.integrations = integrations;
        this.catalogue = catalogue;
    }

    /** What a caller sends to connect one. */
    public record ConnectBody(UUID workspaceId, String provider, String category, String displayName,
                              String credentialType, Map<String, String> fields, List<String> scopes,
                              String connectedAccount) {
    }

    /** A reason, for switching one off. */
    public record ReasonBody(String reason) {
    }

    /** What an adapter needs from the owner, which is the answer to "what do we have to do". */
    @GetMapping("/{provider}/requirements")
    @PreAuthorize(SEE)
    @Operation(summary = "What this adapter needs from the owner, in the order it is done")
    public Map<String, Object> requirements(@PathVariable String provider) {
        return IntegrationAdapterCatalogue.requirementsOf(provider);
    }

    /** The whole catalogue, grouped, with how many of the adapters actually exist. */
    @GetMapping("/catalogue")
    @PreAuthorize(SEE)
    @Operation(summary = "Every adapter, and which of them are implemented here")
    public Map<String, Object> catalogueView() {
        return catalogue.catalogue();
    }

    /** What this account has connected, with the honest status of each. */
    @GetMapping
    @PreAuthorize(SEE)
    @Operation(summary = "What this account has connected, with its status")
    public List<Map<String, Object>> connections() {
        return catalogue.connections(tenant());
    }

    /**
     * Connects an integration.
     *
     * <p>The credentials go in and never come back out. The answer says whether any were stored, because
     * that is the question an interface has, and not what they are.</p>
     */
    @PostMapping
    @PreAuthorize(CONFIGURE)
    @Operation(summary = "Connects an integration; the credentials are stored encrypted and never returned")
    public ResponseEntity<Map<String, Object>> connect(@RequestBody ConnectBody body) {
        UUID tenantId = tenant();
        UUID actor = CurrentUser.required();
        if (body.provider() == null || body.provider().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Eine Anbindung braucht einen Anbieter");
        }
        IntegrationService.Connection connection = integrations.connect(tenantId, body.workspaceId(),
                body.provider(), body.category(), body.displayName(), body.credentialType(),
                body.fields() == null ? Map.of() : body.fields(),
                body.scopes() == null ? List.of() : body.scopes(), body.connectedAccount(), actor);

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("integrationId", connection.integrationId().toString());
        result.put("status", connection.status());
        // Whether anything was stored, never what it was.
        result.put("credentialsStored", connection.credentialsStored());
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    /**
     * Records that an adapter the specification names does not exist here.
     *
     * <p>Stored with the schema's own {@code NOT_SUPPORTED} status rather than refused: the customer's wish
     * is worth keeping, and a row saying "not supported" is the honest answer that "not configured" is not
     * — the first tells a person to look for another way, the second sends them looking for a setting that
     * does not exist.</p>
     */
    @PostMapping("/unsupported")
    @PreAuthorize(CONFIGURE)
    @Operation(summary = "Records an adapter this software does not have, as NOT_SUPPORTED")
    public ResponseEntity<Map<String, Object>> declareUnsupported(@RequestBody ConnectBody body) {
        UUID tenantId = tenant();
        UUID actor = CurrentUser.required();
        if (body.provider() == null || body.provider().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Eine Anbindung braucht einen Anbieter");
        }
        Map<String, Object> result = catalogue.declareUnsupported(tenantId, body.workspaceId(),
                body.provider(), actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    /** Switches a connection off. What it did before that is not erased. */
    @PostMapping("/{integrationId}/disable")
    @PreAuthorize("hasAnyAuthority('integrations.disconnect','integrations.configure')")
    @Operation(summary = "Switches a connection off; what it did before is not erased")
    public ResponseEntity<Void> disable(@PathVariable UUID integrationId,
                                        @RequestBody(required = false) ReasonBody body) {
        integrations.disable(tenant(), integrationId, body == null ? null : body.reason());
        return ResponseEntity.noContent().build();
    }

    /**
     * Starts a synchronisation.
     *
     * <p>The status comes back as the provider reported it rather than as success: an integration that
     * half-worked has to be able to say so, or nobody finds out until the figures are wrong.</p>
     */
    @PostMapping("/{integrationId}/sync")
    @PreAuthorize(CONFIGURE)
    @Operation(summary = "Starts a synchronisation; the run reports its own status")
    public ResponseEntity<Map<String, Object>> sync(@PathVariable UUID integrationId,
                                                    @RequestParam(defaultValue = "INCREMENTAL")
                                                    String runType) {
        UUID tenantId = tenant();
        IntegrationService.SyncRun run = integrations.startSync(tenantId, integrationId, runType,
                CurrentUser.required());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("runId", run.runId().toString(),
                "integrationId", run.integrationId().toString(), "status", run.status()));
    }

    /** The tenant the caller is acting in, which is never taken from a body. */
    private UUID tenant() {
        return RequestContext.tenantId().orElseThrow();
    }
}
