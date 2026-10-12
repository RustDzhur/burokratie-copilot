package de.buerokratiecopilot.notifications;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.notifications.NotificationChannelAdapter.Channel;
import de.buerokratiecopilot.notifications.NotificationChannelAdapter.DeliveryResult;
import de.buerokratiecopilot.notifications.NotificationChannelAdapter.Payload;
import de.buerokratiecopilot.notifications.domain.Notification;
import de.buerokratiecopilot.notifications.domain.NotificationDelivery;
import de.buerokratiecopilot.notifications.domain.NotificationPreference;
import de.buerokratiecopilot.shared.error.ApiException;

/**
 * The notification centre (spec §25).
 *
 * <p>Two rules carry the acceptance criteria, and both are about not pretending:</p>
 *
 * <p><strong>A channel the user switched off does not send.</strong> The attempt is recorded as
 * {@code SUPPRESSED} with the reason, which is the user's decision and not a failure — a distinction
 * that matters when somebody asks why they were not told.</p>
 *
 * <p><strong>A confidential notification does not carry its details onto a channel a stranger can
 * read.</strong> A push message appears on a locked screen, and an e-mail subject appears in a
 * preview; both therefore carry neutral wording, and the notification itself stays in the application
 * where the person has authenticated.</p>
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notifications;
    private final NotificationPreferenceRepository preferences;
    private final NotificationDeliveryRepository deliveries;
    private final List<NotificationChannelAdapter> adapters;

    public NotificationService(NotificationRepository notifications,
                               NotificationPreferenceRepository preferences,
                               NotificationDeliveryRepository deliveries,
                               List<NotificationChannelAdapter> adapters) {
        this.notifications = notifications;
        this.preferences = preferences;
        this.deliveries = deliveries;
        this.adapters = adapters;
    }

    /** What to notify about. */
    public record Request(UUID tenantId, UUID userId, String recipientAddress, String notificationType,
                          Notification.Category category, Notification.Severity severity,
                          String titleKey, String bodyKey, Map<String, Object> parameters,
                          String entityType, UUID entityId, String actionUrl, boolean confidential,
                          String dedupKey) {
    }

    /** What happened on each channel, for the caller and for the tests. */
    public record Dispatch(Notification notification, Map<Channel, NotificationDelivery> deliveries) {

        public Optional<NotificationDelivery> of(Channel channel) {
            return Optional.ofNullable(deliveries.get(channel));
        }

        public boolean sentOn(Channel channel) {
            return of(channel).map(delivery -> delivery.getStatus() == NotificationDelivery.Status.SENT)
                    .orElse(false);
        }
    }

    /**
     * Creates a notification and delivers it on every channel that is allowed and able.
     *
     * <p>The notification itself is always stored: it is the record in the application, and switching
     * a channel off must not mean losing the information.</p>
     */
    @Transactional
    public Dispatch notify(Request request) {
        Notification notification = new Notification(UUID.randomUUID(), request.tenantId(),
                request.userId(), request.notificationType(), request.category(), request.titleKey());
        notification.setSeverity(request.severity() == null ? Notification.Severity.INFO
                : request.severity());
        notification.setBodyKey(request.bodyKey());
        notification.setParameters(request.parameters());
        notification.setEntityType(request.entityType());
        notification.setEntityId(request.entityId());
        notification.setActionUrl(request.actionUrl());
        notification.setConfidential(request.confidential());
        notification.setDedupKey(dedupKeyOf(request));

        Notification saved = notifications.save(notification);

        Map<Channel, NotificationDelivery> byChannel = new LinkedHashMap<>();
        byChannel.put(Channel.IN_APP, recordInApp(saved));

        for (NotificationChannelAdapter adapter : adapters) {
            if (adapter.channel() == Channel.IN_APP) {
                continue;
            }
            byChannel.put(adapter.channel(), deliver(saved, adapter, request));
        }

        log.info("Notification {} of type {} dispatched: {}", saved.getId(), saved.getNotificationType(),
                byChannel.entrySet().stream()
                        .map(entry -> entry.getKey() + "=" + entry.getValue().getStatus())
                        .toList());
        return new Dispatch(saved, byChannel);
    }

    /** The notification exists in the application; that is what "in-app delivered" means. */
    private NotificationDelivery recordInApp(Notification notification) {
        NotificationDelivery delivery = deliveries.save(new NotificationDelivery(UUID.randomUUID(),
                notification.getTenantId(), notification.getId(), Channel.IN_APP));
        delivery.markSent(null);
        return deliveries.save(delivery);
    }

    /**
     * Delivers on one channel, or records why it did not happen.
     *
     * <p>Order matters: the user's preference is checked before the adapter's availability, because
     * "you switched this off" and "this deployment cannot send" are different answers.</p>
     */
    private NotificationDelivery deliver(Notification notification, NotificationChannelAdapter adapter,
                                         Request request) {
        NotificationDelivery delivery = deliveries.save(new NotificationDelivery(UUID.randomUUID(),
                notification.getTenantId(), notification.getId(), adapter.channel()));

        Optional<NotificationPreference> preference = preferences
                .findByUserIdAndCategoryAndChannel(notification.getUserId(), notification.getCategory(),
                        adapter.channel());
        if (preference.isPresent() && !preference.get().allowsDelivery()) {
            delivery.markSuppressed("The user switched this channel off for this category");
            return deliveries.save(delivery);
        }

        if (!adapter.isAvailable()) {
            // Nothing was sent and nothing was decided: recorded as skipped so nobody believes a
            // message went out.
            delivery.markSkipped(adapter.unavailableReason());
            return deliveries.save(delivery);
        }

        Payload payload = payloadFor(notification, adapter.channel());
        try {
            adapter.send(request.recipientAddress(), payload);
            delivery.markSent(null);
        }
        catch (RuntimeException ex) {
            log.warn("Notification {} could not be delivered on {}", notification.getId(),
                    adapter.channel(), ex);
            delivery.markFailed(ex.getMessage());
        }
        return deliveries.save(delivery);
    }

    /**
     * The text a channel may carry.
     *
     * <p>A confidential notification becomes neutral wording on every channel except the application
     * itself: it is the only one the user reaches after authenticating. Keeping the rule here, in one
     * place, is what makes it a rule rather than a habit.</p>
     */
    Payload payloadFor(Notification notification, Channel channel) {
        if (notification.isConfidential() && channel != Channel.IN_APP) {
            return Payload.neutral();
        }
        String title = notification.getTitleKey();
        String body = notification.getBodyKey() == null ? "" : notification.getBodyKey();
        return new Payload(title, body);
    }

    /** A stable key from the event, so the same event cannot produce two notifications. */
    private static String dedupKeyOf(Request request) {
        if (request.dedupKey() != null && !request.dedupKey().isBlank()) {
            return request.dedupKey();
        }
        return request.notificationType() + ":" + request.entityType() + ":"
                + (request.entityId() == null ? "-" : request.entityId());
    }

    @Transactional(readOnly = true)
    public List<Notification> inbox(UUID userId, boolean unreadOnly) {
        return unreadOnly ? notifications.findByUserIdAndReadFalseOrderByCreatedAtDesc(userId)
                : notifications.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID userId) {
        return notifications.countByUserIdAndReadFalse(userId);
    }

    @Transactional
    public Notification markRead(UUID notificationId, UUID userId) {
        Notification notification = notifications.findById(notificationId)
                .filter(candidate -> candidate.getUserId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("notification"));
        notification.markRead();
        return notifications.save(notification);
    }

    /** Marks everything the user has not read as read, and says how many that was. */
    @Transactional
    public int markAllRead(UUID userId) {
        List<Notification> unread = notifications.findByUserIdAndReadFalseOrderByCreatedAtDesc(userId);
        unread.forEach(Notification::markRead);
        notifications.saveAll(unread);
        return unread.size();
    }

    @Transactional
    public Notification markUnread(UUID notificationId, UUID userId) {
        Notification notification = notifications.findById(notificationId)
                .filter(candidate -> candidate.getUserId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("notification"));
        notification.markUnread();
        return notifications.save(notification);
    }

    /** The preferences of a user, with the platform defaults filled in for what is not configured. */
    @Transactional(readOnly = true)
    public List<NotificationPreference> preferencesOf(UUID userId) {
        return preferences.findByUserId(userId);
    }

    /**
     * Sets a preference.
     *
     * <p>Switching a channel off is a decision the platform records and honours; switching it on again
     * removes the record rather than storing "enabled", so the default applies again.</p>
     */
    @Transactional
    public NotificationPreference setPreference(UUID tenantId, UUID userId, Notification.Category category,
                                                Channel channel, boolean enabled,
                                                NotificationPreference.Frequency frequency) {
        NotificationPreference preference = preferences
                .findByUserIdAndCategoryAndChannel(userId, category, channel)
                .orElseGet(() -> new NotificationPreference(UUID.randomUUID(), tenantId, userId, category,
                        channel));
        preference.setEnabled(enabled);
        if (frequency != null) {
            preference.setFrequency(frequency);
        }
        if (!enabled) {
            // An explicitly disabled channel is NEVER, so no digest can resurrect it either.
            preference.setFrequency(NotificationPreference.Frequency.NEVER);
        }
        return preferences.save(preference);
    }

    @Transactional(readOnly = true)
    public List<NotificationDelivery> deliveriesOf(UUID notificationId) {
        return new ArrayList<>(deliveries.findByNotificationId(notificationId));
    }
}
