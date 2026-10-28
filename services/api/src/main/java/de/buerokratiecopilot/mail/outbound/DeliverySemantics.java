package de.buerokratiecopilot.mail.outbound;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a message state actually means (spec §30, E-002 acceptance).
 *
 * <p>The acceptance criterion is that handing a message to the provider is not presented as delivery.
 * The distinction is not a matter of wording: a provider accepting a message means it has taken
 * responsibility for trying, and nothing more. Mail is regularly accepted and then bounced, and a
 * platform that showed "delivered" at the moment of acceptance would have told the user something it
 * does not know.</p>
 *
 * <p>The database already says the same thing — {@code message_delivery_events.proves_delivery} may only
 * be true for {@code DELIVERED}, {@code READ} and {@code REPLIED}. This class is the same rule in code,
 * so the platform cannot even attempt to record something the schema would refuse.</p>
 *
 * <p>Nothing here claims legal delivery. The specification forbids it, and the platform has no way to
 * know whether a letter was served: it knows what a provider reported and who confirmed what. Claims
 * about legal service belong to a person, and the code does not make them.</p>
 */
public final class DeliverySemantics {

    /** Event types a provider may report, in the order they normally arrive. */
    public enum EventType {
        QUEUED, SUBMITTED, ACCEPTED_BY_PROVIDER, DELIVERED, READ, BOUNCED, COMPLAINED, REPLIED, FAILED,
        RETRY_SCHEDULED
    }

    /** The states an outbound message can be in. */
    public enum Status {
        DRAFT, PENDING_APPROVAL, APPROVED, QUEUED, SENT, DELIVERED, BOUNCED, FAILED, CANCELLED
    }

    /**
     * The event types that prove the message reached the recipient.
     *
     * <p>Exactly the three the schema allows. {@code ACCEPTED_BY_PROVIDER} is deliberately absent: it is
     * the single most common thing mistaken for delivery.</p>
     */
    private static final Set<EventType> PROVING = Set.of(EventType.DELIVERED, EventType.READ,
            EventType.REPLIED);

    /** The event types that mean the message will not arrive. */
    private static final Set<EventType> FAILING = Set.of(EventType.BOUNCED, EventType.FAILED);

    private DeliverySemantics() {
    }

    /** Whether an event proves the message arrived. */
    public static boolean provesDelivery(EventType eventType) {
        return eventType != null && PROVING.contains(eventType);
    }

    /**
     * The message state that follows from an event.
     *
     * <p>A later event never downgrades an earlier one that proved delivery: a bounce arriving after a
     * delivery receipt refers to something else, and moving the message back would lose the fact that it
     * arrived.</p>
     */
    public static Status statusAfter(Status current, EventType eventType) {
        if (eventType == null) {
            return current;
        }
        if (current == Status.DELIVERED && !provesDelivery(eventType)) {
            // Already proven delivered: a later bounce or failure report refers to something else, and
            // moving the message back would lose the fact that it arrived.
            return Status.DELIVERED;
        }
        return switch (eventType) {
            case DELIVERED, READ, REPLIED -> Status.DELIVERED;
            case BOUNCED -> Status.BOUNCED;
            case FAILED -> Status.FAILED;
            case RETRY_SCHEDULED -> Status.QUEUED;
            // Handed over, nothing more. The message is on its way, and that is all the platform knows.
            case QUEUED, SUBMITTED, ACCEPTED_BY_PROVIDER -> Status.SENT;
            case COMPLAINED -> current;
        };
    }

    /**
     * What the platform may say about a message, in words a user reads.
     *
     * <p>The wording is the point of this method. "Übergeben" is what happened when a provider accepted
     * the message; "zugestellt" is only said when something proved it.</p>
     */
    public static String description(Status status) {
        return switch (status) {
            case DRAFT -> "Entwurf, noch nicht freigegeben";
            case PENDING_APPROVAL -> "Wartet auf Freigabe durch eine Person";
            case APPROVED -> "Freigegeben, noch nicht übergeben";
            case QUEUED -> "Zur Übergabe vorgemerkt";
            case SENT -> "An den Dienst übergeben; eine Zustellung ist damit nicht bestätigt";
            case DELIVERED -> "Zustellung durch den Dienst bestätigt";
            case BOUNCED -> "Nicht zustellbar; der Dienst hat die Nachricht zurückgewiesen";
            case FAILED -> "Übergabe fehlgeschlagen";
            case CANCELLED -> "Abgebrochen";
        };
    }

    /**
     * Whether a claim of legal service may be made about this message.
     *
     * <p>Always false, and it is a method rather than an omission so that a caller asking the question
     * gets an answer instead of inventing one. The platform records what a provider reported and who
     * confirmed what; whether a letter was legally served is a question for a person with the file in
     * front of them.</p>
     */
    public static boolean supportsLegalServiceClaim(Status status) {
        return false;
    }

    /** The reason a message may not be sent yet, or {@code null} when it may. */
    public static String blockingReasonForSending(Status status, boolean approved, boolean isReplyToIncoming) {
        if (status == Status.CANCELLED || status == Status.BOUNCED || status == Status.FAILED) {
            return "Die Nachricht ist " + status + " und wird nicht gesendet";
        }
        if (!approved) {
            // The acceptance criterion: an answer to an incoming letter is not sent by itself. A draft
            // that a person has not released stays a draft, however obviously it looks like a reply.
            return isReplyToIncoming
                    ? "Eine Antwort auf eine eingegangene Nachricht wird nicht ohne Freigabe durch eine "
                            + "Person gesendet"
                    : "Die Nachricht wurde nicht freigegeben";
        }
        if (status == Status.SENT || status == Status.DELIVERED) {
            return "Die Nachricht wurde bereits übergeben";
        }
        return null;
    }

    /** The event types a provider may send, with what each one means. */
    public static List<Map<String, Object>> meaningOfEveryEvent() {
        return java.util.Arrays.stream(EventType.values())
                .map(type -> Map.<String, Object>of(
                        "eventType", type.name(),
                        "provesDelivery", provesDelivery(type),
                        "failsDelivery", FAILING.contains(type)))
                .toList();
    }
}
