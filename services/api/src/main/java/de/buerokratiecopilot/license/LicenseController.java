package de.buerokratiecopilot.license;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public licence status of the runtime.
 *
 * <p>It is deliberately reachable without a token: the installer, the monitoring system and the
 * first operator need to see whether a key is installed before any credential exists. It exposes
 * plan metadata and the instance id, never the key, the signature or the secret.</p>
 */
@RestController
@RequestMapping("/api/v1/license")
public class LicenseController {

    private final LicenseService license;

    public LicenseController(LicenseService license) {
        this.license = license;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> status() {
        LicenseService.LicenseStatus status = license.status();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enforced", license.isEnforced());
        body.put("state", status.state().name());
        body.put("readOnly", status.readOnly());
        body.put("reason", status.reason());
        body.put("licenseId", status.licenseId());
        body.put("customer", status.customer());
        body.put("instanceId", status.instanceId());
        body.put("plan", status.plan());
        body.put("maxAgents", status.maxAgents());
        body.put("maxRunsPerMonth", status.maxRunsPerMonth());
        body.put("features", status.features());
        body.put("issuedAt", status.issuedAt());
        body.put("expiresAt", status.expiresAt());
        body.put("perpetual", status.perpetual());
        body.put("daysRemaining", status.daysRemaining());
        body.put("graceDaysRemaining", status.graceDaysRemaining());
        body.put("revokedAt", status.revokedAt());
        body.put("revocationReason", status.revocationReason());
        body.put("revocationCheckedAt", status.revocationCheckedAt());
        body.put("checkedAt", status.checkedAt());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
