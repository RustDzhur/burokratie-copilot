package de.buerokratiecopilot.mail.oauth;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The two providers whose mailboxes can be connected with OAuth (spec §7.2).
 *
 * <p>The name in the URL ({@code microsoft}, {@code google}) and the name the database uses
 * ({@code MICROSOFT365}, {@code GMAIL}) are deliberately not the same: the first is what a browser link
 * says, the second is what {@code mail_accounts.provider} allows. This enum is the one place that knows
 * both, so a new spelling cannot silently end up in a column the check constraint refuses.</p>
 */
public enum MailOAuthProvider {

    MICROSOFT365("microsoft", "MICROSOFT365", "Microsoft 365",
            List.of("microsoft", "microsoft365", "m365", "outlook")),
    GMAIL("google", "GMAIL", "Google Workspace",
            List.of("google", "google-workspace", "googleworkspace", "gmail"));

    private final String path;
    private final String databaseValue;
    private final String displayName;
    private final List<String> aliases;

    MailOAuthProvider(String path, String databaseValue, String displayName, List<String> aliases) {
        this.path = path;
        this.databaseValue = databaseValue;
        this.displayName = displayName;
        this.aliases = aliases;
    }

    /** The name in the URL: {@code /api/v1/mail/oauth/microsoft/start}. */
    public String path() {
        return path;
    }

    /** The value {@code mail_accounts.provider} accepts. */
    public String databaseValue() {
        return databaseValue;
    }

    public String displayName() {
        return displayName;
    }

    /** Looks a provider up by the URL path, accepting the spellings a person or a link might use. */
    public static Optional<MailOAuthProvider> fromPath(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return Optional.empty();
        }
        String normalized = candidate.strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(provider -> provider.path.equals(normalized) || provider.aliases.contains(normalized))
                .findFirst();
    }

    /** Looks a provider up by the value stored in {@code mail_accounts.provider}. */
    public static Optional<MailOAuthProvider> fromDatabaseValue(String candidate) {
        if (candidate == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(provider -> provider.databaseValue.equals(candidate)).findFirst();
    }

    /** Whether a mailbox of this provider is authenticated with tokens instead of a password. */
    public static boolean isOAuthProvider(String databaseValue) {
        return fromDatabaseValue(databaseValue).isPresent();
    }
}
