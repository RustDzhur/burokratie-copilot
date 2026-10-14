package de.buerokratiecopilot.orchestration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.notifications.NotificationService;
import de.buerokratiecopilot.notifications.domain.Notification;

/**
 * Tells the owners of a workspace that something needs them, in words and with a link.
 *
 * <p>A failure here is logged and swallowed: a notification that cannot be stored must not undo the
 * processing it reports. The dedup key makes a repeated report (a retried event, a watchdog cycle) land on
 * the same notification instead of producing a new one each time.</p>
 */
@Component
public class OwnerNotifier {

    private static final Logger log = LoggerFactory.getLogger(OwnerNotifier.class);

    private final JdbcTemplate jdbc;
    private final NotificationService notifications;

    public OwnerNotifier(JdbcTemplate jdbc, NotificationService notifications) {
        this.jdbc = jdbc;
        this.notifications = notifications;
    }

    public void tell(UUID tenantId, Notification.Category category, Notification.Severity severity,
                     String type, String title, String body, String entityType, UUID entityId,
                     String actionUrl, String dedupKey) {
        List<UUID> owners = jdbc.query("""
                select user_id from memberships
                where tenant_id = ? and status = 'ACTIVE' and is_owner
                """, (rs, i) -> (UUID) rs.getObject(1), tenantId);
        tell(tenantId, owners, category, severity, type, title, body, entityType, entityId, actionUrl, dedupKey);
    }

    public void tell(UUID tenantId, List<UUID> recipients, Notification.Category category,
                     Notification.Severity severity, String type, String title, String body,
                     String entityType, UUID entityId, String actionUrl, String dedupKey) {
        for (UUID recipient : recipients) {
            try {
                Map<String, Object> parameters = new LinkedHashMap<>();
                parameters.put("title", title);
                parameters.put("body", body);
                notifications.notify(new NotificationService.Request(tenantId, recipient, null, type, category,
                        severity, type, type + ".body", parameters, entityType, entityId, actionUrl, false,
                        dedupKey));
            }
            catch (RuntimeException ex) {
                log.warn("Notification '{}' for {} could not be stored: {}", title, recipient, ex.getMessage());
            }
        }
    }
}
