package de.buerokratiecopilot.mail.outbound;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import jakarta.mail.internet.MimeMessage;

/**
 * Hands messages to an SMTP server (spec §30, E-002).
 *
 * <p>The only thing this class claims is that the server accepted the message. SMTP says nothing about
 * delivery — a relay accepts and may bounce later, and even a final server's acceptance is not proof
 * that a person saw anything. The event the service records afterwards says exactly that.</p>
 *
 * <p>If no mail server is configured, the sender says so instead of pretending: a message with nowhere
 * to go stays a draft, and the user is told that rather than watching it silently disappear.</p>
 *
 * <p><strong>Attachments are written into the MIME message, not mentioned in it.</strong> What was wrong:
 * the draft carried the document ids and the message that left contained the text only — the receiver got
 * an invoice letter without the invoice, and nothing anywhere said so. Each attachment is added as its own
 * part with the file name and the type the document holds, so the receiver's client shows a file it can
 * open instead of an unnamed blob.</p>
 */
@Component
public class SmtpOutboundSender implements OutboundSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpOutboundSender.class);

    private final ObjectProvider<JavaMailSender> mailSender;

    public SmtpOutboundSender(ObjectProvider<JavaMailSender> mailSender) {
        this.mailSender = mailSender;
    }

    @Override
    public String send(UUID tenantId, String from, List<String> to, List<String> cc, String subject,
                       String body, List<Attachment> attachments) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            throw new SendFailedException("NO_MAIL_SERVER",
                    "Es ist kein Postausgang konfiguriert; die Nachricht bleibt ein Entwurf", null);
        }
        if (to == null || to.isEmpty()) {
            throw new SendFailedException("NO_RECIPIENT", "Die Nachricht hat keinen Empfänger", null);
        }

        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            if (from != null && !from.isBlank()) {
                helper.setFrom(from);
            }
            helper.setTo(to.toArray(String[]::new));
            if (cc != null && !cc.isEmpty()) {
                helper.setCc(cc.toArray(String[]::new));
            }
            helper.setSubject(subject);
            helper.setText(body == null ? "" : body, false);
            int attached = 0;
            if (attachments != null) {
                for (Attachment attachment : attachments) {
                    if (attachment == null || attachment.content() == null) {
                        continue;
                    }
                    // The type is passed explicitly: a part without one arrives as application/octet-stream
                    // and the receiver has to guess what the invoice is.
                    helper.addAttachment(attachment.filename(),
                            new ByteArrayResource(attachment.content()),
                            attachment.contentType() == null ? "application/octet-stream"
                                    : attachment.contentType());
                    attached++;
                }
            }

            sender.send(message);
            // The server accepted it. The identifier is the Message-ID the message carries; after a real
            // handover the transport has written it. The header is read instead of calling the getter,
            // because that getter invents an identifier from the local host name when none is set - and
            // that lookup waits on a reverse DNS answer on some networks. A message without one is
            // reported as "accepted", which is all this layer can honestly say.
            String[] messageIdHeader = message.getHeader("Message-ID");
            String messageId = messageIdHeader == null || messageIdHeader.length == 0
                    ? null : messageIdHeader[0];
            String reported = messageId == null ? "accepted" : messageId;
            log.info("Message handed to SMTP for tenant {} as {} with {} attachment(s)", tenantId,
                    reported, attached);
            return reported;
        }
        catch (MailException | jakarta.mail.MessagingException ex) {
            throw new SendFailedException("SMTP_FAILED",
                    "Der Postausgang hat die Nachricht nicht angenommen: " + ex.getMessage(), ex);
        }
    }

    @Override
    public boolean isConfigured(UUID tenantId) {
        return mailSender.getIfAvailable() != null;
    }
}
