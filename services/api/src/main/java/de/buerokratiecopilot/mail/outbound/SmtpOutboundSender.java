package de.buerokratiecopilot.mail.outbound;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
                       String body) {
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

            sender.send(message);
            // The server accepted it. The identifier is the message id it assigned, which is the most
            // this layer can honestly report.
            String messageId = message.getMessageID();
            log.info("Message handed to SMTP for tenant {} as {}", tenantId, messageId);
            return messageId == null ? "accepted" : messageId;
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
