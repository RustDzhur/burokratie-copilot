package de.buerokratiecopilot.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import de.buerokratiecopilot.shared.mail.EmailSender;

/**
 * Chooses the notification channels (spec §25).
 *
 * <p>An adapter that cannot send is registered anyway, and it answers that it cannot. Its deliveries
 * are then recorded as {@code SKIPPED} with the reason, so the platform never claims a message went
 * out when no provider exists — and when one is configured, only the adapter changes.</p>
 */
@Configuration(proxyBeanMethods = false)
public class NotificationConfiguration {

    private static final Logger log = LoggerFactory.getLogger(NotificationConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(name = "emailNotificationAdapter")
    public NotificationChannelAdapter emailNotificationAdapter(EmailSender sender) {
        NotificationChannelAdapter adapter = new NotificationChannelAdapter.EmailAdapter(sender);
        log.info("Notification channel EMAIL: {}", adapter.isAvailable() ? "available"
                : adapter.unavailableReason());
        return adapter;
    }

    @Bean
    @ConditionalOnMissingBean(name = "pushNotificationAdapter")
    public NotificationChannelAdapter pushNotificationAdapter(
            de.buerokratiecopilot.shared.config.BurokratieProperties properties) {
        // No push provider is implemented yet (task F-004). The adapter exists so the channel's privacy
        // rule is decided and tested now, and it reports honestly that it cannot send.
        boolean available = false;
        NotificationChannelAdapter adapter = new NotificationChannelAdapter.PushAdapter(available);
        log.info("Notification channel PUSH: {}", available ? "available" : adapter.unavailableReason());
        return adapter;
    }
}
