package de.buerokratiecopilot.notifications;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import de.buerokratiecopilot.notifications.NotificationChannelAdapter.Channel;
import de.buerokratiecopilot.notifications.domain.Notification;
import de.buerokratiecopilot.notifications.domain.NotificationPreference;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, UUID> {

    List<NotificationPreference> findByUserId(UUID userId);

    Optional<NotificationPreference> findByUserIdAndCategoryAndChannel(UUID userId,
                                                                       Notification.Category category,
                                                                       Channel channel);

    List<NotificationPreference> findByUserIdAndCategory(UUID userId, Notification.Category category);
}
