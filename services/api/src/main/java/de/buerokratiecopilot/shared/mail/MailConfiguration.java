package de.buerokratiecopilot.shared.mail;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;

import de.buerokratiecopilot.shared.config.BurokratieProperties;

/**
 * Registers the outgoing mail implementation.
 *
 * <p>Declaring the default as a conditional bean (instead of a component with a
 * self-referencing {@code @ConditionalOnMissingBean}) keeps the door open for a deployment that
 * plugs in its own {@link EmailSender} — for example a transactional provider or a tenant
 * specific sender — without changing the application.</p>
 */
@Configuration(proxyBeanMethods = false)
public class MailConfiguration {

    @Bean
    @ConditionalOnMissingBean(EmailSender.class)
    public EmailSender emailSender(Environment environment, BurokratieProperties properties,
                                   ObjectProvider<JavaMailSender> mailSender) {
        // Without spring.mail.host there is no JavaMailSender bean at all; the provider keeps
        // the application startable and the sender reports itself as not configured.
        return new DefaultEmailSender(environment, properties, mailSender.getIfAvailable());
    }
}
