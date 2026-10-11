package de.buerokratiecopilot.shared.context;

import java.util.Optional;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The authenticated user of the current request.
 *
 * <p>The session filter puts the user identifier into the authentication principal. Reading it through
 * one helper keeps that detail in a single place instead of in every controller, and makes it obvious
 * that an action without an authenticated user is a programming error rather than an anonymous one.</p>
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static Optional<UUID> id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(authentication.getName()));
        }
        catch (IllegalArgumentException ex) {
            // An anonymous or pre-authentication token has a name that is not an identifier.
            return Optional.empty();
        }
    }

    /** The user identifier, or a failure when an action requires one and none is present. */
    public static UUID required() {
        return id().orElseThrow(() -> new IllegalStateException("This action requires an authenticated user"));
    }
}
