package de.buerokratiecopilot.shared.mail;

/**
 * Raised when a message could not be handed to a mail provider.
 *
 * <p>Callers must propagate it: a registration that silently "succeeded" without delivering the
 * verification link would leave the user locked out (spec §1.2: no fake success).</p>
 */
public class EmailDeliveryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EmailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }

    public EmailDeliveryException(String message) {
        super(message);
    }
}
