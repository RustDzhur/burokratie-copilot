package de.buerokratiecopilot.shared.mail;

/**
 * Outgoing e-mail abstraction.
 *
 * <p>The platform must work without a configured mail provider during development, but it must
 * never silently drop a message that a user depends on (verification link, invitation,
 * password reset). Implementations therefore either deliver the message or fail loudly.</p>
 */
public interface EmailSender {

    /**
     * Sends a message.
     *
     * @throws EmailDeliveryException when the message could not be handed to a provider
     */
    void send(EmailMessage message);

    /** Whether the sender is actually able to deliver mail right now. */
    boolean isConfigured();
}
