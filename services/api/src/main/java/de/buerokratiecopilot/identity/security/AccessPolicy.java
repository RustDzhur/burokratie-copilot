package de.buerokratiecopilot.identity.security;

import java.util.UUID;

/**
 * Two decisions about a signed-in person that do not come from their role: whether their workspace may use the
 * platform at all right now (subscription), and whether they run the platform (platform administrator).
 */
public interface AccessPolicy {

    /** Used where no policy is provided (tests of the security layer alone): nothing is blocked. */
    AccessPolicy OPEN = new AccessPolicy() {
        @Override
        public boolean blocks(UUID tenantId, String path) {
            return false;
        }

        @Override
        public boolean isPlatformAdmin(UUID userId) {
            return false;
        }
    };

    /** True when the workspace's access ended and this path is not one of the few that stay open (pay, sign out). */
    boolean blocks(UUID tenantId, String path);

    boolean isPlatformAdmin(UUID userId);

    /**
     * Why this path is closed to the workspace although its access has not ended: its plan or its kind of account does
     * not include the area. Null when it may be used.
     */
    default String featureDenial(UUID tenantId, String path) {
        return null;
    }
}
