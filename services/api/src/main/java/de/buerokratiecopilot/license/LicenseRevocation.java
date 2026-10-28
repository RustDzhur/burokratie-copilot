package de.buerokratiecopilot.license;

import java.time.Instant;

/**
 * What the vendor's cabinet last said about a licence.
 *
 * <p>{@code revoked} is the only field that changes behaviour. {@code checkedAt} records when the
 * answer was received, so an operator can tell "active as of yesterday" from "active, checked a
 * minute ago". {@code reason} is a short, vendor-supplied sentence and is shown to the operator;
 * it contains no key material.</p>
 */
public record LicenseRevocation(boolean revoked, Instant revokedAt, String reason, Instant checkedAt) {

    /** Before any check has run: the installation trusts the signed key. */
    public static LicenseRevocation unknown() {
        return new LicenseRevocation(false, null, null, null);
    }
}
