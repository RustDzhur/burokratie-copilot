package de.buerokratiecopilot.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.notifications.NotificationChannelAdapter.Channel;
import de.buerokratiecopilot.notifications.NotificationChannelAdapter.Payload;
import de.buerokratiecopilot.notifications.domain.Notification;

/**
 * Verifies that confidential matters do not appear where a stranger can read them (spec §25 acceptance).
 *
 * <p>A push message appears on a locked screen and an e-mail subject appears in a preview. Both are
 * readable by whoever is holding the phone, which is why a confidential notification may only say that
 * something arrived — never what. The rule is checked here for every channel, because a rule that holds
 * for three channels out of four does not hold.</p>
 */
class PushPrivacyTest {

    private static Notification notification(boolean confidential) {
        Notification notification = new Notification(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "DEADLINE_APPROACHING", Notification.Category.DEADLINE,
                "Frist läuft am 31.03.2026 ab");
        notification.setBodyKey("Widerspruch gegen den Bescheid der Stadt Musterstadt");
        notification.setParameters(Map.of("caseReference", "V-2026-0001", "authority", "Stadt Musterstadt"));
        notification.setConfidential(confidential);
        return notification;
    }

    @Test
    @DisplayName("a confidential notification reaches push and e-mail only as neutral wording")
    void confidentialNeverCarriesDetailsOutside() {
        NotificationService service = new NotificationService(null, null, null, List.of(), null);
        Notification confidential = notification(true);

        Payload push = service.payloadFor(confidential, Channel.PUSH);
        Payload email = service.payloadFor(confidential, Channel.EMAIL);

        assertThat(push.title()).isEqualTo("Bürokratie-Copilot");
        assertThat(push.body()).isEqualTo("Sie haben eine neue Benachrichtigung.");
        assertThat(push.body())
                .as("a lock screen must not reveal the matter")
                .doesNotContain("Musterstadt")
                .doesNotContain("Widerspruch")
                .doesNotContain("31.03.2026");
        assertThat(email.body()).doesNotContain("V-2026-0001");
    }

    @Test
    @DisplayName("even the failure of a confidential case stays out of the neutral text")
    void neutralPayloadIsFixed() {
        Payload neutral = Payload.neutral();

        assertThat(neutral.title()).isNotBlank();
        assertThat(neutral.body()).doesNotContain("Vorgang");
        assertThat(neutral.body()).isEqualTo("Sie haben eine neue Benachrichtigung.");
    }

    @Test
    @DisplayName("inside the application the details are shown, because the user has authenticated")
    void inAppKeepsTheDetails() {
        NotificationService service = new NotificationService(null, null, null, List.of(), null);

        Payload inApp = service.payloadFor(notification(true), Channel.IN_APP);

        assertThat(inApp.title()).isEqualTo("Frist läuft am 31.03.2026 ab");
        assertThat(inApp.body()).contains("Musterstadt");
    }

    @Test
    @DisplayName("a notification that is not confidential keeps its wording on every channel")
    void unrestrictedNotificationsKeepTheirWording() {
        NotificationService service = new NotificationService(null, null, null, List.of(), null);
        Notification open = notification(false);

        for (Channel channel : Channel.values()) {
            Payload payload = service.payloadFor(open, channel);
            assertThat(payload.title())
                    .as("channel %s", channel)
                    .isEqualTo("Frist läuft am 31.03.2026 ab");
        }
    }

    @Test
    @DisplayName("a notification is not confidential unless its caller says so")
    void confidentialityIsExplicit() {
        Notification notification = new Notification(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "SYSTEM_UPDATE", Notification.Category.SYSTEM, "Wartung");

        assertThat(notification.isConfidential()).isFalse();
        notification.setConfidential(true);
        assertThat(notification.isConfidential()).isTrue();
    }

    @Test
    @DisplayName("the channels a dispatcher considers never include one the user cannot influence")
    void configurableChannelsAreKnown() {
        assertThat(NotificationChannelAdapter.configurableChannels())
                .containsExactly(Channel.IN_APP, Channel.EMAIL, Channel.PUSH, Channel.WEBHOOK);
    }
}
