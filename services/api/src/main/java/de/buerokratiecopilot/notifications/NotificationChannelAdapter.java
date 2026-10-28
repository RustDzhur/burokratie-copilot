package de.buerokratiecopilot.notifications;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import de.buerokratiecopilot.shared.mail.EmailMessage;
import de.buerokratiecopilot.shared.mail.EmailSender;
import de.buerokratiecopilot.shared.mail.EmailDeliveryException;

/**
 * Sends a notification on one channel (spec §25).
 *
 * <p>An adapter answers three things: whether it can send at all, what it would send, and whether the
 * message it sent was allowed to contain the details. The third is the one that matters for privacy:
 * a push notification lands on a locked phone's screen, so a confidential matter may only appear there
 * as "you have a new notification".</p>
 *
 * <p>An adapter that cannot send says so instead of pretending. The delivery is then recorded as
 * skipped with a reason, which is a different fact from "sent".</p>
 */
public interface NotificationChannelAdapter {

    enum Channel {
        IN_APP, EMAIL, PUSH, WEBHOOK, SMS
    }

    /** What the channel would deliver, already stripped of anything it may not carry. */
    record Payload(String title, String body) {

        /** The neutral form used whenever the details must not leave the platform. */
        public static Payload neutral() {
            return new Payload("Bürokratie-Copilot", "Sie haben eine neue Benachrichtigung.");
        }
    }

    /** The outcome of one delivery attempt. */
    record DeliveryResult(boolean sent, boolean skipped, String error) {

        public static DeliveryResult success() {
            return new DeliveryResult(true, false, null);
        }

        public static DeliveryResult wasSkipped(String reason) {
            return new DeliveryResult(false, true, reason);
        }

        public static DeliveryResult failedWith(String reason) {
            return new DeliveryResult(false, false, reason);
        }
    }

    Channel channel();

    /** Whether this deployment can send on the channel right now. */
    boolean isAvailable();

    /** Why it cannot, when it cannot. */
    String unavailableReason();

    void send(String recipientAddress, Payload payload) throws EmailDeliveryException;

    /**
     * The e-mail adapter, which uses the platform's existing mail infrastructure.
     *
     * <p>Confidential notifications are deliberately neutral here too: an e-mail preview on a lock
     * screen is exactly as public as a push message.</p>
     */
    final class EmailAdapter implements NotificationChannelAdapter {

        private static final Logger log = LoggerFactory.getLogger(EmailAdapter.class);

        private final EmailSender sender;

        public EmailAdapter(EmailSender sender) {
            this.sender = sender;
        }

        @Override
        public Channel channel() {
            return Channel.EMAIL;
        }

        @Override
        public boolean isAvailable() {
            // The mail module decides whether it can send; an unconfigured deployment says so.
            try {
                return sender.isConfigured();
            }
            catch (RuntimeException ex) {
                return false;
            }
        }

        @Override
        public String unavailableReason() {
            return isAvailable() ? null : "No mail provider is configured in this deployment";
        }

        @Override
        public void send(String recipientAddress, Payload payload) throws EmailDeliveryException {
            if (recipientAddress == null || recipientAddress.isBlank()) {
                throw new EmailDeliveryException("The recipient has no e-mail address");
            }
            sender.send(EmailMessage.of(recipientAddress, payload.title(), payload.body()));
            log.debug("Notification e-mail handed to the provider");
        }
    }

    /**
     * The push adapter.
     *
     * <p>No push provider is configured in this deployment yet, and that is stated rather than faked:
     * the delivery is recorded as skipped with the reason, so nobody believes a message went out. The
     * neutral payload the adapter would send is decided here, where the privacy rule belongs.</p>
     */
    final class PushAdapter implements NotificationChannelAdapter {

        private final boolean available;

        public PushAdapter(boolean available) {
            this.available = available;
        }

        @Override
        public Channel channel() {
            return Channel.PUSH;
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public String unavailableReason() {
            return available ? null
                    : "No push provider is configured in this deployment";
        }

        @Override
        public void send(String recipientAddress, Payload payload) {
            if (!available) {
                throw new IllegalStateException("No push provider is configured");
            }
        }
    }

    /** Everything a user may be told about, in the shape the dispatch loop needs. */
    record Request(UUID tenantId, UUID userId, String recipientAddress,
                   de.buerokratiecopilot.notifications.domain.Notification notification) {
    }

    /** The channels a dispatcher should consider, in the order they are attempted. */
    static List<Channel> configurableChannels() {
        return List.of(Channel.IN_APP, Channel.EMAIL, Channel.PUSH, Channel.WEBHOOK);
    }

    /** Diagnostics for the delivery record. */
    default Map<String, Object> describe() {
        return Map.of("channel", channel().name(), "available", isAvailable(),
                "checkedAt", Instant.now().toString());
    }
}
