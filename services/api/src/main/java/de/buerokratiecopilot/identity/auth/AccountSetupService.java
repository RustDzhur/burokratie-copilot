package de.buerokratiecopilot.identity.auth;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.notifications.NotificationService;
import de.buerokratiecopilot.notifications.NotificationChannelAdapter.Channel;
import de.buerokratiecopilot.notifications.domain.Notification;
import de.buerokratiecopilot.notifications.domain.NotificationPreference;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * The first-run setup after registration (spec §5.2.3): name the workspace, choose where documents come from
 * and what to be told about. There is one kind of cabinet, so the wizard no longer asks what the account is
 * for and never changes its type.
 *
 * <p>Only a person who may edit the account's settings renames the workspace. A member who was invited into
 * somebody else's workspace walks through the same wizard, and finishing it must not rename the owner's
 * workspace: for them the wizard only records that it was done and applies the notification choices, which
 * are their own.</p>
 */
@Service
public class AccountSetupService {

    static final String ONBOARDING_KEY = "onboarding";

    private static final List<String> SOURCES = List.of("upload", "email", "scanner");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final NotificationService notifications;

    public AccountSetupService(JdbcTemplate jdbc, ObjectMapper json, NotificationService notifications) {
        this.jdbc = jdbc;
        this.json = json;
        this.notifications = notifications;
    }

    public record Setup(String workspaceName, List<String> sources, List<String> notifications) {
    }

    @Transactional(readOnly = true)
    public boolean isCompleted(UUID userId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from user_preferences where user_id = ? and pref_key = ?", Integer.class,
                userId, ONBOARDING_KEY);
        return count != null && count > 0;
    }

    @Transactional
    public void complete(UUID tenantId, UUID userId, boolean mayEditAccount, Setup setup) {
        List<String> sources = setup.sources() == null ? List.of() : setup.sources();
        for (String source : sources) {
            if (!SOURCES.contains(source)) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "Unbekannte Quelle: " + source);
            }
        }
        List<String> chosen = setup.notifications() == null ? List.of() : setup.notifications();

        if (mayEditAccount) {
            String name = setup.workspaceName() == null ? "" : setup.workspaceName().strip();
            if (!name.isEmpty()) {
                if (name.length() > 200) {
                    throw new ApiException(ErrorCode.INVALID_REQUEST, "Der Name ist zu lang");
                }
                jdbc.update("update tenants set name = ?, updated_at = now() where id = ?", name, tenantId);
                jdbc.update("update workspaces set name = ? where tenant_id = ? and is_default", name, tenantId);
            }
        }

        applyNotificationChoices(tenantId, userId, chosen);

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("completedAt", Instant.now().toString());
        record.put("sources", sources);
        record.put("notifications", chosen);
        try {
            jdbc.update("""
                    insert into user_preferences (user_id, pref_key, pref_value, updated_at)
                    values (?, ?, ?::jsonb, now())
                    on conflict (user_id, pref_key)
                    do update set pref_value = excluded.pref_value, updated_at = now()
                    """, userId, ONBOARDING_KEY, json.writeValueAsString(record));
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("A map of strings cannot fail to serialise", ex);
        }
    }

    /** "Deadlines" and "review" switch those categories on; "weekly" turns the task summary into a digest. */
    private void applyNotificationChoices(UUID tenantId, UUID userId, List<String> chosen) {
        for (String choice : chosen) {
            switch (choice) {
                case "deadlines" -> {
                    enable(tenantId, userId, Notification.Category.DEADLINE);
                    enable(tenantId, userId, Notification.Category.MISSED_DEADLINE);
                }
                case "review" -> enable(tenantId, userId, Notification.Category.DOCUMENT_REVIEW);
                case "weekly" -> notifications.setPreference(tenantId, userId, Notification.Category.TASK,
                        Channel.EMAIL, true, NotificationPreference.Frequency.WEEKLY_DIGEST);
                default -> throw new ApiException(ErrorCode.INVALID_REQUEST,
                        "Unbekannte Benachrichtigung: " + choice);
            }
        }
    }

    private void enable(UUID tenantId, UUID userId, Notification.Category category) {
        for (Channel channel : List.of(Channel.IN_APP, Channel.EMAIL)) {
            notifications.setPreference(tenantId, userId, category, channel, true,
                    NotificationPreference.Frequency.IMMEDIATE);
        }
    }
}
