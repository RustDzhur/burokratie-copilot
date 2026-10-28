package de.buerokratiecopilot.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.ingest.IngestService;
import de.buerokratiecopilot.license.LicenseKeyInstaller;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Operator surface of an API-only runtime installation.
 *
 * <p>It exists because the product has no web UI: without it, creating the first account and
 * revoking a leaked bridge token would require {@code psql}. Every answer is {@code no-store};
 * the bootstrap and issue answers carry a plaintext credential exactly once.</p>
 */
@RestController
@ConditionalOnWebApplication(
        type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "burokratie.runtime", name = "enabled", havingValue = "true")
@RequestMapping("/api/v1/runtime")
public class RuntimeAdminController {

    private static final String ADMIN_HEADER = "X-Runtime-Admin-Token";

    private final RuntimeAdminService admin;
    private final LicenseKeyInstaller licenseInstaller;

    public RuntimeAdminController(RuntimeAdminService admin, LicenseKeyInstaller licenseInstaller) {
        this.admin = admin;
        this.licenseInstaller = licenseInstaller;
    }

    public record BootstrapRequest(String tenantName, String ownerEmail, String ownerName) {
    }

    public record LicenseRequest(String licenseKey) {
    }

    public record TokenRequest(String name, List<String> scopes) {
    }

    @PostMapping("/bootstrap")
    public ResponseEntity<Map<String, Object>> bootstrap(
            @RequestHeader(value = ADMIN_HEADER, required = false) String adminToken,
            @RequestBody(required = false) BootstrapRequest request) {
        admin.requireAdmin(adminToken);
        if (request == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "A bootstrap body is required");
        }
        RuntimeAdminService.BootstrapResult result = admin.bootstrap(request.tenantName(),
                request.ownerEmail(), request.ownerName());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", result.tenantId().toString());
        body.put("workspaceId", result.workspaceId().toString());
        body.put("ownerUserId", result.ownerUserId().toString());
        body.put("ownerEmail", result.ownerEmail());
        body.put("tokenId", result.tokenId().toString());
        body.put("token", result.token());
        body.put("prefix", result.prefix());
        body.put("scopes", result.scopes());
        body.put("rateLimitPerMinute", result.rateLimitPerMinute());
        body.put("apiBase", result.apiBase());
        body.put("warning", "This token is shown once. Store it in the bridge's secret store; "
                + "only its hash is kept on the server.");
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(body);
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(
            @RequestHeader(value = ADMIN_HEADER, required = false) String adminToken) {
        admin.requireAdmin(adminToken);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(admin.status());
    }

    @GetMapping("/tokens")
    public ResponseEntity<Map<String, Object>> tokens(
            @RequestHeader(value = ADMIN_HEADER, required = false) String adminToken) {
        admin.requireAdmin(adminToken);
        List<Map<String, Object>> items = admin.listTokens().stream()
                .map(IngestService.TokenSummary::asMap)
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("count", items.size());
        body.put("availableScopes", IngestService.SCOPES);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @PostMapping("/tokens")
    public ResponseEntity<Map<String, Object>> issue(
            @RequestHeader(value = ADMIN_HEADER, required = false) String adminToken,
            @RequestBody(required = false) TokenRequest request) {
        admin.requireAdmin(adminToken);
        RuntimeAdminService.AdminTokenResult issued = admin.issueToken(
                request == null ? null : request.name(),
                request == null ? null : request.scopes());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", issued.tokenId().toString());
        body.put("token", issued.token());
        body.put("prefix", issued.prefix());
        body.put("scopes", issued.scopes());
        body.put("rateLimitPerMinute", issued.rateLimitPerMinute());
        body.put("warning", "This token is shown once. Only its hash is stored.");
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(body);
    }

    /**
     * Installs a licence key that the operator pasted into the admin page.
     *
     * <p>The key is verified before the file is replaced, so a typo cannot turn a licensed
     * installation into an unlicensed one. The answer carries the licence status after the
     * reload, so the page can show the expiry and any warning in one round trip.</p>
     */
    @PostMapping("/license")
    public ResponseEntity<Map<String, Object>> installLicense(
            @RequestHeader(value = ADMIN_HEADER, required = false) String adminToken,
            @RequestBody(required = false) LicenseRequest request) {
        admin.requireAdmin(adminToken);
        if (request == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "A licence body is required");
        }
        LicenseKeyInstaller.InstallResult result = licenseInstaller.install(request.licenseKey());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("installed", true);
        body.put("keyFile", result.keyFile());
        body.put("state", result.license().state().name());
        body.put("warnings", result.warnings());
        body.put("license", result.license());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @PostMapping("/tokens/{id}/revoke")
    public ResponseEntity<Void> revoke(
            @RequestHeader(value = ADMIN_HEADER, required = false) String adminToken,
            @PathVariable UUID id) {
        admin.requireAdmin(adminToken);
        admin.revokeToken(id);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
