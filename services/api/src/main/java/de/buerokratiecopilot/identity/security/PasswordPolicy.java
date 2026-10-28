package de.buerokratiecopilot.identity.security;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

import de.buerokratiecopilot.shared.config.BurokratieProperties;

/**
 * Password policy (spec §21.3).
 *
 * <p>Length is the dominant factor, so the rule requires a long password instead of a
 * complicated character-class puzzle. In addition the most common passwords are rejected,
 * and a password may not contain the user's own e-mail or name — the two checks that actually
 * stop credential stuffing from succeeding.</p>
 */
@Component
public class PasswordPolicy {

    /** A short list of passwords that appear in every breach corpus. */
    private static final Set<String> FORBIDDEN = Set.of(
            "password", "passwort", "123456", "12345678", "123456789", "qwerty", "qwertz",
            "letmein", "welcome", "admin", "administrator", "iloveyou", "monkey", "dragon",
            "passwort1", "password1", "password123", "p@ssw0rd", "abc123", "111111", "000000",
            "geheim", "hallo123", "willkommen", "sommer2024", "sommer2025", "sommer2026");

    private final BurokratieProperties properties;

    public PasswordPolicy(BurokratieProperties properties) {
        this.properties = properties;
    }

    /**
     * @return the list of violations; empty means the password is acceptable. The caller turns
     *         them into a localized validation error.
     */
    public List<String> validate(String password, String email, String displayName) {
        int min = properties.getSecurity().getPassword().getMinLength();
        int max = properties.getSecurity().getPassword().getMaxLength();

        if (password == null || password.isBlank()) {
            return List.of("password.required");
        }
        List<String> violations = new java.util.ArrayList<>();
        if (password.length() < min) {
            violations.add("password.too_short");
        }
        if (password.length() > max) {
            violations.add("password.too_long");
        }
        if (password.chars().distinct().count() < 5) {
            violations.add("password.too_repetitive");
        }

        String normalized = password.toLowerCase(Locale.ROOT);
        if (FORBIDDEN.contains(normalized)) {
            violations.add("password.too_common");
        }
        if (email != null) {
            String localPart = email.toLowerCase(Locale.ROOT).split("@")[0];
            if (localPart.length() >= 4 && normalized.contains(localPart)) {
                violations.add("password.contains_email");
            }
        }
        if (displayName != null && displayName.length() >= 4
                && normalized.contains(displayName.toLowerCase(Locale.ROOT))) {
            violations.add("password.contains_name");
        }
        return List.copyOf(violations);
    }

    public boolean isAcceptable(String password, String email, String displayName) {
        return validate(password, email, displayName).isEmpty();
    }
}
