package de.buerokratiecopilot.license;

/**
 * The states a runtime installation can be in.
 *
 * <p>Only five of them matter to a client: the product is licensed ({@code LICENSE_ACTIVE}), it is
 * licensed but the key has expired and the grace window is running ({@code LICENSE_GRACE}), the key
 * is missing or invalid ({@code LICENSE_MISSING}), the grace window is over and the
 * installation is read-only ({@code LICENSE_READ_ONLY}), or the vendor has revoked the key
 * ({@code LICENSE_REVOKED}). {@code LICENSE_NOT_ENFORCED} exists for development and tests, where
 * no licence is checked at all.</p>
 *
 * <p>A perpetual licence (no {@code expiresAt}) is simply {@code LICENSE_ACTIVE}; it never reaches
 * {@code LICENSE_GRACE} or {@code LICENSE_READ_ONLY}. Revocation is the only way such a licence
 * stops working, and it stops immediately — no grace window applies to a revoked key.</p>
 */
public enum LicenseState {

    LICENSE_NOT_ENFORCED,
    LICENSE_ACTIVE,
    LICENSE_GRACE,
    LICENSE_READ_ONLY,
    LICENSE_REVOKED,
    LICENSE_MISSING;

    /** Whether agent and workflow calls may execute. */
    public boolean agentCallsAllowed() {
        return this == LICENSE_NOT_ENFORCED || this == LICENSE_ACTIVE || this == LICENSE_GRACE;
    }

    /** Whether the installation may only be read. */
    public boolean readOnly() {
        return this == LICENSE_READ_ONLY;
    }
}
