package de.buerokratiecopilot.billing;

import java.util.UUID;

import org.springframework.stereotype.Component;

import de.buerokratiecopilot.identity.security.AccessPolicy;

/**
 * Closes the API for a workspace whose access has ended. What stays open is what a customer needs to get back in:
 * signing in and out, the second factor, and the billing area where the plan is renewed or the data is dealt
 * with.
 */
@Component
public class SubscriptionAccessPolicy implements AccessPolicy {

    private final SubscriptionService subscriptions;
    private final PlatformAdminResolver admins;

    public SubscriptionAccessPolicy(SubscriptionService subscriptions, PlatformAdminResolver admins) {
        this.subscriptions = subscriptions;
        this.admins = admins;
    }

    @Override
    public boolean blocks(UUID tenantId, String path) {
        if (path.startsWith("/api/v1/billing") || path.startsWith("/api/v1/auth/") || path.startsWith("/api/v1/mfa")
                || path.startsWith("/api/v1/admin")) {
            return false;
        }
        return subscriptions.isBlocked(tenantId);
    }

    @Override
    public boolean isPlatformAdmin(UUID userId) {
        return admins.isPlatformAdmin(userId);
    }
}
