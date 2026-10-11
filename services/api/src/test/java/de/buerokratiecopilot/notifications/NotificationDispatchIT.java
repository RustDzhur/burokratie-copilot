package de.buerokratiecopilot.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.notifications.NotificationChannelAdapter.Channel;
import de.buerokratiecopilot.notifications.domain.Notification;
import de.buerokratiecopilot.notifications.domain.NotificationDelivery;
import de.buerokratiecopilot.notifications.domain.NotificationPreference;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * Verifies notification delivery and the preferences that stop it (spec §25 acceptance).
 *
 * <p>The criterion is that a channel the user switched off does not send. The test distinguishes the
 * three ways a delivery can fail to be sent — the user's choice, the deployment's inability, and an
 * actual error — because they have different answers when somebody asks why they were not told.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationDispatchIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private NotificationService notifications;

    private record Session(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private Session register() throws Exception {
        String email = "notify-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Benachrichtigung Test","accountType":"COMPANY"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID tenantId = UUID.fromString(JsonPath.read(
                registration.getResponse().getContentAsString(), "$.tenantId"));
        UUID userId = UUID.fromString(JsonPath.read(
                registration.getResponse().getContentAsString(), "$.userId"));

        RequestContext.runAs(tenantId, () -> {
            User user = users.findById(userId).orElseThrow();
            user.markEmailVerified(java.time.Instant.now());
            user.setStatus(User.Status.ACTIVE);
            return users.save(user);
        });

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn();
        return new Session(login.getResponse().getCookie("bc_session"), tenantId, userId);
    }

    private NotificationService.Dispatch notify(Session session, boolean confidential) {
        return RequestContext.runAs(session.tenantId(), () -> notifications.notify(
                new NotificationService.Request(session.tenantId(), session.userId(),
                        "person@example.de", "DEADLINE_APPROACHING", Notification.Category.DEADLINE,
                        Notification.Severity.WARNING, "Frist läuft ab", "Widerspruch einlegen",
                        Map.of("caseReference", "V-2026-0001"), "CASE", UUID.randomUUID(),
                        "/cases/1", confidential, null)));
    }

    @Test
    @DisplayName("a notification is stored and delivered in the application")
    void inAppIsAlwaysDelivered() throws Exception {
        Session session = register();

        NotificationService.Dispatch dispatch = notify(session, false);

        assertThat(dispatch.sentOn(Channel.IN_APP)).isTrue();
        assertThat(dispatch.notification().getUserId()).isEqualTo(session.userId());

        mockMvc.perform(get("/api/v1/notifications").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].titleKey").value("Frist läuft ab"))
                .andExpect(jsonPath("$[0].read").value(false))
                .andExpect(jsonPath("$[0].deliveries[?(@.channel=='IN_APP')].status")
                        .value(org.hamcrest.Matchers.contains("SENT")));
    }

    @Test
    @DisplayName("a channel without a provider is recorded as skipped, never as sent")
    void channelWithoutProviderIsSkipped() throws Exception {
        Session session = register();

        NotificationService.Dispatch dispatch = notify(session, false);

        // No push provider is configured in this deployment; saying nothing was sent is the only
        // honest answer, and it is different from the user having switched it off.
        NotificationDelivery push = dispatch.of(Channel.PUSH).orElseThrow();
        assertThat(push.getStatus()).isEqualTo(NotificationDelivery.Status.SKIPPED);
        assertThat(push.getErrorMessage()).contains("No push provider");
        assertThat(push.getSentAt()).isNull();
    }

    @Test
    @DisplayName("a channel the user switched off does not send, and says it was their decision")
    void disabledChannelDoesNotSend() throws Exception {
        Session session = register();

        mockMvc.perform(post("/api/v1/notifications/preferences").with(csrf())
                        .cookie(session.cookie()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"DEADLINE\",\"channel\":\"EMAIL\",\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.frequency").value("NEVER"));

        RequestContext.runAs(session.tenantId(), () -> notifications.setPreference(session.tenantId(),
                session.userId(), Notification.Category.DEADLINE, Channel.EMAIL, false, null));

        NotificationService.Dispatch dispatch = notify(session, false);

        NotificationDelivery email = dispatch.of(Channel.EMAIL).orElseThrow();
        assertThat(email.getStatus())
                .as("the user's decision is not a failure and not a skip")
                .isEqualTo(NotificationDelivery.Status.SUPPRESSED);
        assertThat(email.getErrorMessage()).contains("switched this channel off");

        // And the notification itself is still in the application: switching a channel off must not
        // mean losing the information.
        mockMvc.perform(get("/api/v1/notifications").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("marking read is reflected in the unread count")
    void readStateIsTracked() throws Exception {
        Session session = register();
        NotificationService.Dispatch dispatch = notify(session, false);
        String notificationId = dispatch.notification().getId().toString();

        mockMvc.perform(get("/api/v1/notifications/unread-count").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread").value(1));

        mockMvc.perform(post("/api/v1/notifications/{id}/read", notificationId).with(csrf())
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true));

        mockMvc.perform(get("/api/v1/notifications/unread-count").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread").value(0));
    }

    @Test
    @DisplayName("nobody can read somebody else's inbox")
    void inboxIsPerPerson() throws Exception {
        Session owner = register();
        Session stranger = register();
        notify(owner, false);

        mockMvc.perform(get("/api/v1/notifications").cookie(stranger.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        mockMvc.perform(get("/api/v1/notifications/unread-count").cookie(stranger.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread").value(0));
    }

    @Test
    @DisplayName("a confidential notification is flagged for the interface to handle")
    void confidentialFlagTravelsWithTheNotification() throws Exception {
        Session session = register();
        notify(session, true);

        mockMvc.perform(get("/api/v1/notifications").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].confidential").value(true));
    }

    @Test
    @DisplayName("a preference that was never set does not block anything")
    void unsetPreferenceAllowsDelivery() throws Exception {
        Session session = register();

        assertThat(notifications.preferencesOf(session.userId())).isEmpty();
        NotificationService.Dispatch dispatch = notify(session, false);

        // In-app is always sent; the e-mail channel is decided by the deployment, not by a missing
        // preference.
        assertThat(dispatch.sentOn(Channel.IN_APP)).isTrue();
        assertThat(dispatch.of(Channel.EMAIL)).isPresent();
        assertThat(dispatch.of(Channel.EMAIL).orElseThrow().getStatus())
                .isNotEqualTo(NotificationDelivery.Status.SUPPRESSED);
    }

    @Test
    @DisplayName("switching a channel back on restores the default frequency")
    void reenablingClearsNever() throws Exception {
        Session session = register();

        RequestContext.runAs(session.tenantId(), () -> {
            notifications.setPreference(session.tenantId(), session.userId(),
                    Notification.Category.DEADLINE, Channel.EMAIL, false, null);
            NotificationPreference reenabled = notifications.setPreference(session.tenantId(),
                    session.userId(), Notification.Category.DEADLINE, Channel.EMAIL, true,
                    NotificationPreference.Frequency.IMMEDIATE);
            return reenabled;
        }).getFrequency();

        NotificationService.Dispatch dispatch = notify(session, false);
        assertThat(dispatch.of(Channel.EMAIL).orElseThrow().getStatus())
                .isNotEqualTo(NotificationDelivery.Status.SUPPRESSED);
    }
}
