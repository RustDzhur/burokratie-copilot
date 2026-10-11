package de.buerokratiecopilot.mail.outbound;

import java.util.List;
import java.util.UUID;

/**
 * Hands a message to whatever service is configured for a tenant (spec §30, E-002).
 *
 * <p>An interface rather than a direct call to a mail library, because the acceptance criterion is about
 * what a caller may conclude, and that must not depend on which provider is behind it. SMTP, an API or
 * manual export all produce the same answer here: the message was handed over, and delivery is a
 * separate question.</p>
 */
public interface OutboundSender {

    /**
     * Hands a message over.
     *
     * @return the identifier the service assigned
     * @throws SendFailedException when the handover did not happen; the caller records the failure and
     *                             does not claim anything about the message
     */
    String send(UUID tenantId, String from, List<String> to, List<String> cc, String subject, String body);

    /** Whether a service is configured; a message with nowhere to go is a draft, not a failure. */
    boolean isConfigured(UUID tenantId);

    /** A handover that did not happen. */
    class SendFailedException extends RuntimeException {

        private final String code;

        public SendFailedException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
