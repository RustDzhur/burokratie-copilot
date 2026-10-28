package de.buerokratiecopilot.ingest.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.ingest.IngestService;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * The administration screen for API tokens (`docs/AGENT_INTEGRATION.md` §4).
 *
 * <p>{@code IngestService} could issue a token and check one, and no screen could reach either: a
 * credential that only exists if somebody calls a service in a test is a credential a customer cannot
 * use. This controller is the missing door.</p>
 *
 * <p><strong>The plaintext exists in exactly one answer.</strong> It is returned by {@code POST
 * /api-tokens} and by nothing else — not by the list, not by a reload, not by an audit line. Only its
 * hash is stored, so a database copy does not hand an attacker every integration's key.</p>
 *
 * <p><strong>Every answer is {@code no-store}.</strong> The create answer carries a working credential;
 * a browser cache, a proxy or the back-forward cache would hand it to the next person at the same
 * machine. The list is also uncached because it names the integrations a workspace has.</p>
 *
 * <p>Session-authenticated and guarded by {@code api_tokens.manage}: a token is a way around the role
 * model of the person who calls the API with it, so issuing one is an administrative act, not a
 * day-to-day one.</p>
 */
@RestController
@RequestMapping("/api/v1/api-tokens")
@Tag(name = "API tokens", description = "Credentials for external systems")
public class ApiTokenController {

    private final IngestService tokens;

    public ApiTokenController(IngestService tokens) {
        this.tokens = tokens;
    }

    /** What the create form sends: a human-readable name and the scopes the integration may use. */
    public record CreateTokenRequest(String name, List<String> scopes) {
    }

    private static UUID tenant() {
        return RequestContext.tenantId().orElseThrow();
    }

    /**
     * The tokens of the account, newest first, and the scopes a new one may be given.
     *
     * <p>The scope catalogue travels with the list so the checkboxes on the screen cannot drift from
     * what the server validates: there is one list, and it is this one.</p>
     */
    @GetMapping
    @PreAuthorize("hasAuthority('api_tokens.manage')")
    @Operation(summary = "Lists the API tokens of the account without their hashes")
    public ResponseEntity<Map<String, Object>> list() {
        List<Map<String, Object>> items = tokens.listTokens(tenant()).stream()
                .map(IngestService.TokenSummary::asMap)
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("count", items.size());
        body.put("availableScopes", IngestService.SCOPES);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    /**
     * Issues a token. The answer contains the plaintext once; the list never does.
     */
    @PostMapping
    @PreAuthorize("hasAuthority('api_tokens.manage')")
    @Operation(summary = "Issues a token; the value is shown in this answer only")
    public ResponseEntity<Map<String, Object>> create(
            @RequestBody(required = false) CreateTokenRequest request) {
        if (request == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Der Antrag fehlt");
        }
        UUID tenantId = tenant();
        // The token belongs to the workspace this account works in; the ingest path files what it
        // receives there, and the per-workspace limit counts against that workspace.
        IngestService.IssuedToken issued = tokens.issueToken(tenantId,
                tokens.defaultWorkspaceId(tenantId), request.name(), request.scopes(), null, null,
                CurrentUser.required());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", issued.tokenId().toString());
        body.put("name", request.name() == null ? null : request.name().strip());
        body.put("prefix", issued.prefix());
        body.put("token", issued.token());
        body.put("scopes", issued.scopes());
        body.put("rateLimitPerMinute", issued.rateLimitPerMinute());
        body.put("createdAt",
                issued.createdAt() == null ? null : issued.createdAt().toString());
        body.put("warning",
                "Dieser Token wird nur einmal angezeigt. Kopieren Sie ihn jetzt und legen Sie ihn im "
                        + "Zielsystem ab; danach lässt er sich nicht erneut abrufen. Gespeichert wird "
                        + "nur sein Hash.");
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(body);
    }

    /** Revokes a token; a token that is already revoked stays revoked, which is the same answer. */
    @PostMapping("/{id}/revoke")
    @PreAuthorize("hasAuthority('api_tokens.manage')")
    @Operation(summary = "Revokes a token; it answers 401 from then on")
    public ResponseEntity<Void> revoke(@PathVariable UUID id) {
        tokens.revokeToken(tenant(), id, CurrentUser.required());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
