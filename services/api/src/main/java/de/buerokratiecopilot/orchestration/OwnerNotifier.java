package de.buerokratiecopilot.orchestration;

import java.math.BigDecimal;
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
    private final de.buerokratiecopilot.shared.settings.SettingsService settings;
    private final de.buerokratiecopilot.telegram.TelegramInformer telegram;

    public OwnerNotifier(JdbcTemplate jdbc, NotificationService notifications,
                         de.buerokratiecopilot.shared.settings.SettingsService settings,
                         de.buerokratiecopilot.telegram.TelegramInformer telegram) {
        this.telegram = telegram;
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.settings = settings;
    }

    /**
     * The facts a message about a document is built from, as data and not as prose.
     *
     * <p>The screen writes the sentence in the language the person has chosen; the backend sends what the
     * sentence is about: the file, the type (in all three languages) and, for a document waiting for a decision,
     * why: no type could be named, the type always needs a person, or the platform is not sure enough. A message
     * written here in one fixed language was shown to a Russian-speaking user in English.</p>
     */
    public Map<String, Object> documentFacts(UUID documentId) {
        Map<String, Object> facts = new LinkedHashMap<>();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select d.original_filename as filename, d.document_type_id is not null as typed,
                       coalesce(t.name ->> 'de', st.name ->> 'de') as de,
                       coalesce(t.name ->> 'en', st.name ->> 'en') as en,
                       coalesce(t.name ->> 'ru', st.name ->> 'ru') as ru,
                       c.confidence, coalesce(st.requires_review, false) as always_review,
                       c.document_type_id is not null as suggested
                from documents d
                left join document_types t on t.id = d.document_type_id
                left join document_classifications c on c.document_id = d.id and c.is_current
                left join document_types st on st.id = c.document_type_id
                where d.id = ?
                """, documentId);
        if (rows.isEmpty()) {
            return facts;
        }
        Map<String, Object> row = rows.get(0);
        facts.put("documentId", documentId.toString());
        facts.put("filename", row.get("filename"));
        if (row.get("de") != null) {
            Map<String, Object> names = new LinkedHashMap<>();
            names.put("de", row.get("de"));
            names.put("en", row.get("en"));
            names.put("ru", row.get("ru"));
            facts.put("typeName", names);
        }
        if (row.get("confidence") instanceof BigDecimal confidence) {
            facts.put("confidence", Math.round(confidence.doubleValue() * 100));
        }
        facts.put("acceptThreshold", Math.round(settings.getDouble("classification.auto_accept_threshold", 0.85) * 100));
        facts.put("reviewKind", !Boolean.TRUE.equals(row.get("suggested")) ? "NO_MATCH"
                : Boolean.TRUE.equals(row.get("always_review")) ? "TYPE_ALWAYS_REVIEW" : "LOW_CONFIDENCE");
        return facts;
    }

    public void tell(UUID tenantId, Notification.Category category, Notification.Severity severity,
                     String type, String title, String body, String entityType, UUID entityId,
                     String actionUrl, String dedupKey) {
        tell(tenantId, category, severity, type, title, body, entityType, entityId, actionUrl, dedupKey, Map.of());
    }

    public void tell(UUID tenantId, Notification.Category category, Notification.Severity severity,
                     String type, String title, String body, String entityType, UUID entityId,
                     String actionUrl, String dedupKey, Map<String, Object> facts) {
        List<UUID> owners = jdbc.query("""
                select user_id from memberships
                where tenant_id = ? and status = 'ACTIVE' and is_owner
                """, (rs, i) -> (UUID) rs.getObject(1), tenantId);
        tell(tenantId, owners, category, severity, type, title, body, entityType, entityId, actionUrl, dedupKey,
                facts);
    }

    public void tell(UUID tenantId, List<UUID> recipients, Notification.Category category,
                     Notification.Severity severity, String type, String title, String body,
                     String entityType, UUID entityId, String actionUrl, String dedupKey) {
        tell(tenantId, recipients, category, severity, type, title, body, entityType, entityId, actionUrl, dedupKey,
                Map.of());
    }

    public void tell(UUID tenantId, List<UUID> recipients, Notification.Category category,
                     Notification.Severity severity, String type, String title, String body,
                     String entityType, UUID entityId, String actionUrl, String dedupKey,
                     Map<String, Object> facts) {
        for (UUID recipient : recipients) {
            try {
                // title and body stay as a German fallback for channels that cannot render keys (e-mail); the
                // screen builds its own text, in the user's language, from the type and these facts.
                Map<String, Object> parameters = new LinkedHashMap<>(facts);
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
        // The same news, in Telegram, for those who linked it (never breaks the notification above).
        telegram.mirror(tenantId, recipients, type, facts);
    }
}
