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
 *
 * <p><strong>Attachments are bytes, not identifiers.</strong> The draft stores document ids, because a
 * document the account already holds is the only thing that may be attached. What leaves the house is
 * the file itself, so the caller resolves the ids before handing the message over. A sender that received
 * ids instead would have to reach back into the account, and the one thing it must never do is send a
 * letter whose attachment it silently dropped.</p>
 */
public interface OutboundSender {

    /** One file that goes with a message. The name and type travel with it, because the receiver sees those. */
    record Attachment(UUID documentId, String filename, String contentType, byte[] content) {
    }

    /** A message without attachments, for callers that have none. */
    default String send(UUID tenantId, String from, List<String> to, List<String> cc, String subject,
                        String body) {
        return send(tenantId, from, to, cc, subject, body, List.of());
    }

    /**
     * Hands a message over.
     *
     * @return the identifier the service assigned
     * @throws SendFailedException when the handover did not happen; the caller records the failure and
     *                             does not claim anything about the message
     */
    String send(UUID tenantId, String from, List<String> to, List<String> cc, String subject, String body,
                List<Attachment> attachments);

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
