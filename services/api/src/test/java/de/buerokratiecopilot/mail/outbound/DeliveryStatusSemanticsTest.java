package de.buerokratiecopilot.mail.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.mail.outbound.DeliverySemantics.EventType;
import de.buerokratiecopilot.mail.outbound.DeliverySemantics.Status;

/**
 * Verifies what a delivery state means (spec §30, E-002 acceptance).
 *
 * <p>The acceptance criterion is that handing a message to a provider is not presented as delivery.
 * These tests are about the words as much as about the states: a platform that shows "zugestellt" when
 * the provider merely accepted the message has told the user something it does not know, and the user
 * will act on it — by not chasing a letter that never arrived.</p>
 */
class DeliveryStatusSemanticsTest {

    @Test
    @DisplayName("a provider accepting a message is not a delivery")
    void acceptanceByProviderIsNotDelivery() {
        assertThat(DeliverySemantics.provesDelivery(EventType.ACCEPTED_BY_PROVIDER))
                .as("this is the single most common thing mistaken for delivery")
                .isFalse();
        assertThat(DeliverySemantics.provesDelivery(EventType.SUBMITTED)).isFalse();
        assertThat(DeliverySemantics.provesDelivery(EventType.QUEUED)).isFalse();
    }

    @Test
    @DisplayName("exactly the three events the schema allows prove delivery")
    void onlyTheProvingEventsProve() {
        assertThat(DeliverySemantics.provesDelivery(EventType.DELIVERED)).isTrue();
        assertThat(DeliverySemantics.provesDelivery(EventType.READ)).isTrue();
        assertThat(DeliverySemantics.provesDelivery(EventType.REPLIED)).isTrue();

        // The database has the same rule; this checks the code agrees with it rather than the other way
        // round, so the platform cannot even try to record something the schema would refuse.
        assertThat(java.util.Arrays.stream(EventType.values())
                .filter(DeliverySemantics::provesDelivery).toList())
                .containsExactlyInAnyOrder(EventType.DELIVERED, EventType.READ, EventType.REPLIED);
    }

    @Test
    @DisplayName("handing a message over leaves it sent, not delivered")
    void handoverLeavesTheMessageSent() {
        Status afterHandover = DeliverySemantics.statusAfter(Status.QUEUED,
                EventType.ACCEPTED_BY_PROVIDER);

        assertThat(afterHandover).isEqualTo(Status.SENT);
        assertThat(DeliverySemantics.description(afterHandover))
                .as("the wording has to say that delivery is not confirmed")
                .contains("nicht bestätigt");
        assertThat(DeliverySemantics.description(afterHandover)).doesNotContain("zugestellt");
    }

    @Test
    @DisplayName("only a proving event moves a message to delivered")
    void onlyProvingEventsDeliver() {
        assertThat(DeliverySemantics.statusAfter(Status.SENT, EventType.DELIVERED))
                .isEqualTo(Status.DELIVERED);
        assertThat(DeliverySemantics.statusAfter(Status.SENT, EventType.SUBMITTED))
                .isEqualTo(Status.SENT);
        assertThat(DeliverySemantics.description(Status.DELIVERED)).contains("bestätigt");
    }

    @Test
    @DisplayName("a later event does not undo a proven delivery")
    void deliveryIsNotDowngraded() {
        // A bounce arriving after a delivery receipt refers to something else; moving the message back
        // would lose the fact that it arrived.
        assertThat(DeliverySemantics.statusAfter(Status.DELIVERED, EventType.BOUNCED))
                .isEqualTo(Status.DELIVERED);
        assertThat(DeliverySemantics.statusAfter(Status.DELIVERED, EventType.FAILED))
                .isEqualTo(Status.DELIVERED);
    }

    @Test
    @DisplayName("a bounce and a failure are separate states, and both stop the message")
    void bounceAndFailureAreDistinct() {
        assertThat(DeliverySemantics.statusAfter(Status.SENT, EventType.BOUNCED))
                .isEqualTo(Status.BOUNCED);
        assertThat(DeliverySemantics.statusAfter(Status.SENT, EventType.FAILED))
                .isEqualTo(Status.FAILED);
        assertThat(DeliverySemantics.description(Status.BOUNCED)).contains("zurückgewiesen");
    }

    @Test
    @DisplayName("a reply to an incoming message is never sent without a person's approval")
    void repliesAreNotSentAutomatically() {
        String reason = DeliverySemantics.blockingReasonForSending(Status.PENDING_APPROVAL, false, true);

        assertThat(reason)
                .as("an answer to an incoming letter stays a draft until somebody releases it")
                .contains("ohne Freigabe");
        assertThat(DeliverySemantics.blockingReasonForSending(Status.PENDING_APPROVAL, false, false))
                .contains("nicht freigegeben");
    }

    @Test
    @DisplayName("an approved message may be sent, and an already sent one may not")
    void sendingIsGuarded() {
        assertThat(DeliverySemantics.blockingReasonForSending(Status.APPROVED, true, true)).isNull();
        assertThat(DeliverySemantics.blockingReasonForSending(Status.SENT, true, false))
                .contains("bereits übergeben");
        assertThat(DeliverySemantics.blockingReasonForSending(Status.CANCELLED, true, false))
                .contains("CANCELLED");
    }

    @Test
    @DisplayName("the platform never claims a message was legally served")
    void legalServiceIsNeverClaimed() {
        for (Status status : Status.values()) {
            assertThat(DeliverySemantics.supportsLegalServiceClaim(status))
                    .as("whether a letter was legally served is a question for a person with the file")
                    .isFalse();
        }
    }

    @Test
    @DisplayName("the meaning of every event is available, so no caller has to guess")
    void everyEventHasAMeaning() {
        var meaning = DeliverySemantics.meaningOfEveryEvent();

        assertThat(meaning).hasSize(EventType.values().length);
        assertThat(meaning).allSatisfy(entry -> {
            assertThat(entry).containsKeys("eventType", "provesDelivery", "failsDelivery");
        });
        assertThat(meaning).anySatisfy(entry -> assertThat(entry)
                .containsEntry("eventType", "ACCEPTED_BY_PROVIDER")
                .containsEntry("provesDelivery", false));
    }

    @Test
    @DisplayName("every state has wording a user can read")
    void everyStateIsDescribed() {
        for (Status status : Status.values()) {
            assertThat(DeliverySemantics.description(status))
                    .as("a state without wording would be shown to a user as a code")
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("a retry puts the message back in the queue rather than leaving it failed")
    void retryReturnsToTheQueue() {
        assertThat(DeliverySemantics.statusAfter(Status.FAILED, EventType.RETRY_SCHEDULED))
                .isEqualTo(Status.QUEUED);
    }
}
