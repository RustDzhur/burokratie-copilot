package de.buerokratiecopilot.notifications;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import de.buerokratiecopilot.notifications.domain.NotificationDelivery;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, UUID> {

    List<NotificationDelivery> findByNotificationId(UUID notificationId);

    List<NotificationDelivery> findByStatus(NotificationDelivery.Status status);
}
