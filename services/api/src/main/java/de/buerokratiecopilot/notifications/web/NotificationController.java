package de.buerokratiecopilot.notifications.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.notifications.NotificationChannelAdapter.Channel;
import de.buerokratiecopilot.notifications.NotificationService;
import de.buerokratiecopilot.notifications.domain.Notification;
import de.buerokratiecopilot.notifications.domain.NotificationDelivery;
import de.buerokratiecopilot.notifications.domain.NotificationPreference;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * Notification API (spec §25, §20).
 *
 * <p>The inbox is per person, not per tenant: a notification is addressed to somebody, and the endpoints
 * therefore resolve the user from the session rather than taking an identifier — an API that lets one
 * caller read another's inbox is a data breach with a query parameter.</p>
 */
@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications", description = "The inbox, its read state and the channel preferences")
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    public record PreferenceBody(String category, String channel, boolean enabled, String frequency) {
    }

    public record DeliveryResponse(String channel, String status, String errorMessage, String sentAt) {

        static DeliveryResponse from(NotificationDelivery delivery) {
            return new DeliveryResponse(delivery.getChannel().name(), delivery.getStatus().name(),
                    delivery.getErrorMessage(),
                    delivery.getSentAt() == null ? null : delivery.getSentAt().toString());
        }
    }

    public record NotificationResponse(String id, String type, String category, String severity,
                                       String titleKey, String bodyKey, Map<String, Object> parameters,
                                       String entityType, String entityId, String actionUrl,
                                       boolean read, String createdAt, boolean confidential,
                                       List<DeliveryResponse> deliveries) {

        static NotificationResponse from(Notification notification, List<NotificationDelivery> deliveries) {
            return new NotificationResponse(notification.getId().toString(),
                    notification.getNotificationType(), notification.getCategory().name(),
                    notification.getSeverity().name(), notification.getTitleKey(),
                    notification.getBodyKey(), notification.getParameters(),
                    notification.getEntityType(),
                    notification.getEntityId() == null ? null : notification.getEntityId().toString(),
                    notification.getActionUrl(), notification.isRead(),
                    notification.getCreatedAt().toString(), notification.isConfidential(),
                    deliveries.stream().map(DeliveryResponse::from).toList());
        }
    }

    public record PreferenceResponse(String category, String channel, boolean enabled, String frequency) {

        static PreferenceResponse from(NotificationPreference preference) {
            return new PreferenceResponse(preference.getCategory().name(),
                    preference.getChannel().name(), preference.isEnabled(),
                    preference.getFrequency().name());
        }
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "The caller's notifications, newest first")
    public ResponseEntity<List<NotificationResponse>> inbox(
            @RequestParam(defaultValue = "false") boolean unreadOnly) {
        UUID userId = CurrentUser.required();
        return ResponseEntity.ok(notifications.inbox(userId, unreadOnly).stream()
                .map(notification -> NotificationResponse.from(notification,
                        notifications.deliveriesOf(notification.getId())))
                .toList());
    }

    @GetMapping("/unread-count")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "How many notifications the caller has not read")
    public ResponseEntity<Map<String, Object>> unreadCount() {
        return ResponseEntity.ok(Map.of("unread", notifications.unreadCount(CurrentUser.required())));
    }

    @PostMapping("/{id}/read")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Mark a notification as read")
    public ResponseEntity<NotificationResponse> markRead(@PathVariable UUID id) {
        Notification notification = notifications.markRead(id, CurrentUser.required());
        return ResponseEntity.ok(NotificationResponse.from(notification,
                notifications.deliveriesOf(notification.getId())));
    }

    @PostMapping("/read-all")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Mark every unread notification of the caller as read")
    public ResponseEntity<Map<String, Object>> markAllRead() {
        return ResponseEntity.ok(Map.of("marked", notifications.markAllRead(CurrentUser.required())));
    }

    @PostMapping("/{id}/unread")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Mark a notification as unread again")
    public ResponseEntity<NotificationResponse> markUnread(@PathVariable UUID id) {
        Notification notification = notifications.markUnread(id, CurrentUser.required());
        return ResponseEntity.ok(NotificationResponse.from(notification,
                notifications.deliveriesOf(notification.getId())));
    }

    @GetMapping("/preferences")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "The caller's channel preferences")
    public ResponseEntity<List<PreferenceResponse>> preferences() {
        return ResponseEntity.ok(notifications.preferencesOf(CurrentUser.required()).stream()
                .map(PreferenceResponse::from).toList());
    }

    @org.springframework.web.bind.annotation.PutMapping("/preferences")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Replace the caller's preferences in one step; every row is checked before any is stored")
    public ResponseEntity<List<PreferenceResponse>> replacePreferences(
            @RequestBody List<PreferenceBody> requests) {
        UUID tenantId = RequestContext.tenantId().orElseThrow();
        UUID userId = CurrentUser.required();
        // Parse everything first: a bad category in the last row must not leave the first rows applied.
        record Parsed(Notification.Category category, Channel channel, boolean enabled,
                      NotificationPreference.Frequency frequency) {
        }
        List<Parsed> parsed;
        try {
            parsed = requests.stream().map(request -> new Parsed(
                    Notification.Category.valueOf(request.category()), Channel.valueOf(request.channel()),
                    request.enabled(),
                    request.frequency() == null ? null
                            : NotificationPreference.Frequency.valueOf(request.frequency()))).toList();
        }
        catch (IllegalArgumentException | NullPointerException ex) {
            // A word the platform does not know is the caller's mistake, not a server failure.
            throw new de.buerokratiecopilot.shared.error.ApiException(
                    de.buerokratiecopilot.shared.error.ErrorCode.INVALID_REQUEST,
                    "Unbekannte Kategorie, unbekannter Kanal oder unbekannte Häufigkeit");
        }
        parsed.forEach(row -> notifications.setPreference(tenantId, userId, row.category(), row.channel(),
                row.enabled(), row.frequency()));
        return ResponseEntity.ok(notifications.preferencesOf(userId).stream()
                .map(PreferenceResponse::from).toList());
    }

    @PostMapping("/preferences")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Switch a channel on or off for a category")
    public ResponseEntity<PreferenceResponse> setPreference(@RequestBody PreferenceBody request) {
        NotificationPreference preference = notifications.setPreference(
                RequestContext.tenantId().orElseThrow(), CurrentUser.required(),
                Notification.Category.valueOf(request.category()), Channel.valueOf(request.channel()),
                request.enabled(),
                request.frequency() == null ? null
                        : NotificationPreference.Frequency.valueOf(request.frequency()));
        return ResponseEntity.ok(PreferenceResponse.from(preference));
    }
}
