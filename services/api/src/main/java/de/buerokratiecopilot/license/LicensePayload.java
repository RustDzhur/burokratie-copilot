package de.buerokratiecopilot.license;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A parsed and cryptographically verified licence token.
 *
 * <p>The field names are the wire format produced by {@code scripts/license/mint-license.mjs}.
 * {@code issuedAt} and {@code expiresAt} are Unix seconds, exactly like a JWT's {@code iat} and
 * {@code exp}; keeping them numeric means a licence can be inspected with ordinary JWT tooling.</p>
 *
 * <p><strong>{@code expiresAt} is optional.</strong> A key without it is a perpetual licence: the
 * installation is {@link LicenseState#LICENSE_ACTIVE} forever and never enters a grace window.
 * Only keys that carry {@code expiresAt} keep the old expiry/grace/read-only behaviour, which is
 * retained for the few contracts that still need a fixed term.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LicensePayload(
        int v,
        String licenseId,
        String customer,
        String instanceId,
        String plan,
        int maxAgents,
        long maxRunsPerMonth,
        List<String> features,
        long issuedAt,
        Long expiresAt) {

    /** True when the key carries no expiry and therefore never expires. */
    public boolean perpetual() {
        return expiresAt == null;
    }

    public Instant issuedAtInstant() {
        return Instant.ofEpochSecond(issuedAt);
    }

    /** Null for a perpetual licence. */
    public Instant expiresAtInstant() {
        return expiresAt == null ? null : Instant.ofEpochSecond(expiresAt);
    }
}
