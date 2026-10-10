package de.buerokratiecopilot.shared.storage;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Builds and validates object keys.
 *
 * <p>Key layout: {@code tenants/<tenant-uuid>/<yyyy>/<MM>/<first-two-hash-chars>/<random-uuid>}.
 * The tenant is the first path segment, which makes two properties testable and cheap to enforce:</p>
 * <ol>
 *   <li>a key always belongs to exactly one tenant,</li>
 *   <li>a key cannot escape its tenant prefix by containing {@code ..}, an absolute path or a
 *       separator (path traversal protection, spec §21.2).</li>
 * </ol>
 *
 * <p>The original file name never becomes part of the key: it is attacker-controlled, may contain
 * any character, and storing it would leak information through object listings.</p>
 */
public final class BlobKeys {

    private static final DateTimeFormatter YEAR = DateTimeFormatter.ofPattern("yyyy").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM").withZone(ZoneOffset.UTC);

    private static final Pattern TENANT_SEGMENT =
            Pattern.compile("^tenants/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/.*$");
    private static final Pattern SAFE_KEY = Pattern.compile("^tenants/[0-9a-f\\-]{36}/[0-9]{4}/[0-9]{2}/[0-9a-f]{0,2}/[0-9a-f\\-]{36}$");

    private BlobKeys() {
    }

    /**
     * Creates a fresh key for a tenant.
     *
     * @param contentHash SHA-256 of the content; its first two characters shard the prefix so a
     *                    single storage prefix never grows unbounded
     */
    public static String newKey(UUID tenantId, String contentHash) {
        if (tenantId == null) {
            throw new IllegalArgumentException("A blob key requires a tenant");
        }
        Instant now = Instant.now();
        String shard = contentHash == null || contentHash.length() < 2
                ? "00"
                : contentHash.substring(0, 2).toLowerCase(Locale.ROOT);
        return "tenants/%s/%s/%s/%s/%s".formatted(tenantId, YEAR.format(now), MONTH.format(now), shard,
                UUID.randomUUID());
    }

    /** Rejects keys that are malformed, absolute or contain traversal sequences. */
    public static boolean isValid(String storageKey) {
        if (storageKey == null || storageKey.isBlank() || storageKey.length() > 400) {
            return false;
        }
        if (storageKey.contains("..") || storageKey.startsWith("/") || storageKey.contains("\\")
                || storageKey.contains("//") || storageKey.indexOf('\0') >= 0) {
            return false;
        }
        return SAFE_KEY.matcher(storageKey).matches();
    }

    /** Extracts the tenant a key belongs to, or {@code null} when the key is not well formed. */
    public static UUID tenantOf(String storageKey) {
        if (!isValid(storageKey)) {
            return null;
        }
        var matcher = TENANT_SEGMENT.matcher(storageKey);
        return matcher.matches() ? UUID.fromString(matcher.group(1)) : null;
    }

    /**
     * Asserts that a key belongs to the given tenant.
     *
     * @throws BlobAccessException when the key is malformed or belongs to another tenant
     */
    public static String requireTenant(String storageKey, UUID tenantId) {
        UUID owner = tenantOf(storageKey);
        if (owner == null) {
            throw new BlobAccessException("Malformed storage key");
        }
        if (!owner.equals(tenantId)) {
            // Deliberately no detail about the other tenant.
            throw new BlobAccessException("Storage key does not belong to the current tenant");
        }
        return storageKey;
    }
}
