package de.buerokratiecopilot.mail.oauth;

/**
 * A step of the OAuth conversation that did not succeed, with a short machine-readable reason.
 *
 * <p>The message is for the log and never contains a token, an authorization code or the provider's
 * response body: those are exactly the values that must not end up in a log file. The reason is what the
 * interface turns into a sentence; the details stay on the server.</p>
 */
public class MailOAuthException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String reason;

    public MailOAuthException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    public MailOAuthException(String reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    /** One of {@code state}, {@code denied}, {@code code}, {@code exchange}, {@code userinfo}, {@code storage}. */
    public String reason() {
        return reason;
    }
}
