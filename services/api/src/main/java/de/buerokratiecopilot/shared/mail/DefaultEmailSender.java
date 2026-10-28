package de.buerokratiecopilot.shared.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.SimpleMailMessage;
import de.buerokratiecopilot.shared.config.BurokratieProperties;

/**
 * Mail delivery.
 *
 * <p>Two modes, and the mode is never hidden from the operator:</p>
 * <ul>
 *   <li><strong>SMTP configured</strong> ({@code spring.mail.host}) — messages are delivered and
 *       a delivery failure is raised to the caller.</li>
 *   <li><strong>No SMTP (development/test)</strong> — the message is written to the application
 *       log <em>and</em> the sender reports {@code isConfigured() == false} so the API can tell
 *       the user that mail is not set up. In production this is a startup error, see
 *       {@code ProductionConfigurationGuard}.</li>
 * </ul>
 */
public class DefaultEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(DefaultEmailSender.class);

    private final Environment environment;
    private final BurokratieProperties properties;
    private final JavaMailSender mailSender;

    public DefaultEmailSender(Environment environment, BurokratieProperties properties,
                              @org.springframework.lang.Nullable JavaMailSender mailSender) {
        this.environment = environment;
        this.properties = properties;
        this.mailSender = mailSender;
    }

    @Override
    public void send(EmailMessage message) {
        if (!isConfigured()) {
            // Development fallback: the link has to be reachable somehow, so it is logged.
            // The body is written at INFO but never contains a password.
            log.info("""
                    [MAIL NOT SENT — no SMTP configured]
                    to      : {}
                    subject : {}
                    body    :
                    {}""", message.to(), message.subject(), message.textBody());
            return;
        }

        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(properties.getMail().getFrom());
        mail.setTo(message.to());
        mail.setSubject(message.subject());
        mail.setText(message.textBody());
        try {
            mailSender.send(mail);
        }
        catch (MailException ex) {
            throw new EmailDeliveryException("Could not send mail to the recipient", ex);
        }
    }

    @Override
    public boolean isConfigured() {
        if (mailSender == null) {
            return false;
        }
        String host = environment.getProperty("spring.mail.host");
        if (host == null || host.isBlank()) {
            return false;
        }
        return !(mailSender instanceof JavaMailSenderImpl impl) || impl.getHost() != null;
    }
}
