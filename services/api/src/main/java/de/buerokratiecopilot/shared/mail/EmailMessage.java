package de.buerokratiecopilot.shared.mail;

import java.util.List;

/**
 * A plain text/HTML message. Kept deliberately small: transactional messages of this platform
 * are short and contain a link, not a marketing layout.
 */
public record EmailMessage(String to, String subject, String textBody, String htmlBody,
                           List<Attachment> attachments) {

    public record Attachment(String filename, String contentType, byte[] content) {
    }

    public static EmailMessage of(String to, String subject, String textBody) {
        return new EmailMessage(to, subject, textBody, null, List.of());
    }

    public static EmailMessage of(String to, String subject, String textBody, String htmlBody) {
        return new EmailMessage(to, subject, textBody, htmlBody, List.of());
    }
}
