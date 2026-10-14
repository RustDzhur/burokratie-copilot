package de.buerokratiecopilot.telegram;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Links a person to the bot and sends them what happens in their workspace.
 *
 * <p>A message goes out only to a person who linked their own chat by a code that was shown to them in the platform, and
 * stops when they pause it or block the bot. Nothing is sent when no bot is configured, and no message ever carries a
 * password or a secret: only file names, kinds, deadlines and the like.</p>
 */
@Service
public class TelegramService {

    /** How much a person wants to hear. */
    public enum Level {
        ALL, IMPORTANT
    }

    private static final Logger log = LoggerFactory.getLogger(TelegramService.class);
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final TelegramApi api;
    private final String configuredUsername;
    private volatile String discoveredUsername;

    public TelegramService(JdbcTemplate jdbc, TelegramApi api,
                           @Value("${burokratie.telegram.bot-username:}") String configuredUsername) {
        this.jdbc = jdbc;
        this.api = api;
        this.configuredUsername = configuredUsername == null ? "" : configuredUsername.strip().replaceFirst("^@", "");
    }

    public boolean isConfigured() {
        return api.isConfigured();
    }

    private String botUsername() {
        if (!configuredUsername.isBlank()) {
            return configuredUsername;
        }
        if (discoveredUsername == null && api.isConfigured()) {
            try {
                discoveredUsername = api.botUsername();
            }
            catch (RuntimeException ex) {
                log.warn("The name of the Telegram bot could not be read: {}", ex.getMessage());
            }
        }
        return discoveredUsername == null ? "" : discoveredUsername;
    }

    private static UUID tenant() {
        return RequestContext.tenantId().orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, "No tenant in context"));
    }

    private static UUID user() {
        return RequestContext.userId().orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, "No user in context"));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configured", isConfigured());
        out.put("botUsername", isConfigured() ? botUsername() : "");
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select chat_id is not null as linked, linked_at, level, enabled, link_code, code_expires_at
                from telegram_links where tenant_id = ? and user_id = ?
                """, tenant(), user());
        Map<String, Object> row = rows.isEmpty() ? Map.of() : rows.getFirst();
        out.put("linked", Boolean.TRUE.equals(row.get("linked")));
        out.put("linkedAt", row.get("linked_at"));
        out.put("level", row.getOrDefault("level", "ALL"));
        out.put("enabled", row.getOrDefault("enabled", true));
        boolean pending = row.get("link_code") != null && row.get("code_expires_at") instanceof java.sql.Timestamp ts
                && ts.toInstant().isAfter(java.time.Instant.now());
        String username = botUsername();
        out.put("deepLink", pending && !username.isBlank() ? "https://t.me/" + username + "?start=" + row.get("link_code") : null);
        return out;
    }

    /** A fresh code the person opens the bot with; valid for fifteen minutes. */
    @Transactional
    public void createLinkCode() {
        if (!isConfigured()) {
            throw ApiException.conflict("Der Telegram-Bot ist auf diesem Server nicht eingerichtet");
        }
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        jdbc.update("""
                insert into telegram_links (tenant_id, user_id, link_code, code_expires_at)
                values (?, ?, ?, now() + interval '15 minutes')
                on conflict (tenant_id, user_id) do update
                   set link_code = excluded.link_code, code_expires_at = excluded.code_expires_at
                """, tenant(), user(), code.toString());
    }

    @Transactional
    public void unlink() {
        jdbc.update("delete from telegram_links where tenant_id = ? and user_id = ?", tenant(), user());
    }

    @Transactional
    public void preferences(Level level, Boolean enabled) {
        int changed = jdbc.update("""
                update telegram_links set level = coalesce(?, level), enabled = coalesce(?, enabled)
                where tenant_id = ? and user_id = ?
                """, level == null ? null : level.name(), enabled, tenant(), user());
        if (changed == 0) {
            throw ApiException.notFound("Telegram link");
        }
    }

    /** A message to the person who asked, to prove the connection works. */
    public void sendTest() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select l.chat_id, u.locale from telegram_links l join users u on u.id = l.user_id
                where l.tenant_id = ? and l.user_id = ? and l.chat_id is not null
                """, tenant(), user());
        if (rows.isEmpty()) {
            throw ApiException.conflict("Telegram ist noch nicht verbunden");
        }
        api.sendMessage(((Number) rows.getFirst().get("chat_id")).longValue(),
                TelegramTexts.t(String.valueOf(rows.getFirst().get("locale")), "test"));
    }

    // ------------------------------------------------------------------ sending the news

    private record Recipient(UUID userId, long chatId, String locale) {
    }

    /**
     * Tells everybody in the workspace who linked a chat. {@code important} messages go to everyone; the others only to
     * people who chose to hear everything.
     */
    public void inform(UUID tenantId, boolean important, Function<String, String> text) {
        inform(tenantId, null, important, text);
    }

    public void inform(UUID tenantId, List<UUID> onlyThese, boolean important, Function<String, String> text) {
        if (!api.isConfigured() || tenantId == null) {
            return;
        }
        List<Recipient> recipients = RequestContext.runAs(tenantId, () -> jdbc.query("""
                select l.user_id, l.chat_id, u.locale from telegram_links l join users u on u.id = l.user_id
                where l.tenant_id = ? and l.enabled and l.chat_id is not null
                  and (l.level = 'ALL' or ?)
                """, (rs, i) -> new Recipient((UUID) rs.getObject(1), rs.getLong(2), rs.getString(3)), tenantId,
                important));
        for (Recipient recipient : recipients) {
            if (onlyThese != null && !onlyThese.isEmpty() && !onlyThese.contains(recipient.userId())) {
                continue;
            }
            try {
                api.sendMessage(recipient.chatId(), text.apply(recipient.locale()));
            }
            catch (TelegramApi.TelegramException ex) {
                log.warn("Telegram message to a linked chat failed ({}): {}", ex.status(), ex.getMessage());
                if (ex.status() == 403) {
                    // The person blocked the bot: stop trying.
                    RequestContext.runAs(tenantId, () -> jdbc.update(
                            "update telegram_links set enabled = false where tenant_id = ? and user_id = ?", tenantId,
                            recipient.userId()));
                }
            }
        }
    }

    // ------------------------------------------------------------------ what the person writes to the bot

    /** One message from a chat: the commands of the bot. Never throws. */
    public void handleMessage(long chatId, String rawText) {
        String text = rawText == null ? "" : rawText.strip();
        try {
            if (text.startsWith("/start")) {
                String code = text.length() > 6 ? text.substring(6).strip() : "";
                start(chatId, code);
            }
            else if (text.startsWith("/stop")) {
                setEnabled(chatId, false);
            }
            else if (text.startsWith("/status")) {
                status(chatId);
            }
            else if (text.startsWith("/help")) {
                String locale = localeOf(chatId);
                api.sendMessage(chatId, TelegramTexts.t(locale, locale == null ? "notLinked" : "linked"));
            }
        }
        catch (RuntimeException ex) {
            log.warn("A message to the Telegram bot could not be handled: {}", ex.getMessage());
        }
    }

    private String localeOf(long chatId) {
        List<String> rows = jdbc.query("""
                select u.locale from telegram_links l join users u on u.id = l.user_id where l.chat_id = ?
                """, (rs, i) -> rs.getString(1), chatId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void start(long chatId, String code) {
        if (code.isBlank()) {
            // "/start" alone: resume messages for a linked chat, or explain how to link.
            if (localeOf(chatId) != null) {
                setEnabled(chatId, true);
            }
            else {
                api.sendMessage(chatId, TelegramTexts.t("de", "notLinked") + "\n\n" + TelegramTexts.t("en", "notLinked")
                        + "\n\n" + TelegramTexts.t("ru", "notLinked"));
            }
            return;
        }
        List<UUID> linked = jdbc.query("""
                update telegram_links set chat_id = ?, linked_at = now(), link_code = null, code_expires_at = null,
                       enabled = true
                where link_code = ? and code_expires_at > now()
                returning user_id
                """, (rs, i) -> (UUID) rs.getObject(1), chatId, code);
        if (linked.isEmpty()) {
            api.sendMessage(chatId, TelegramTexts.t("de", "codeInvalid") + "\n\n" + TelegramTexts.t("en", "codeInvalid")
                    + "\n\n" + TelegramTexts.t("ru", "codeInvalid"));
            return;
        }
        // A chat belongs to one person: if it was linked to another login before, that link goes.
        jdbc.update("update telegram_links set chat_id = null, linked_at = null where chat_id = ? and user_id <> ?",
                chatId, linked.getFirst());
        api.sendMessage(chatId, TelegramTexts.t(localeOf(chatId), "linked"));
    }

    private void setEnabled(long chatId, boolean enabled) {
        int changed = jdbc.update("update telegram_links set enabled = ? where chat_id = ?", enabled, chatId);
        String locale = localeOf(chatId);
        api.sendMessage(chatId, changed == 0 ? TelegramTexts.t(locale, "notLinked")
                : TelegramTexts.t(locale, enabled ? "resumed" : "stopped"));
    }

    private void status(long chatId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select l.tenant_id, u.locale from telegram_links l join users u on u.id = l.user_id where l.chat_id = ?
                """, chatId);
        if (rows.isEmpty()) {
            api.sendMessage(chatId, TelegramTexts.t("de", "notLinked"));
            return;
        }
        UUID tenantId = (UUID) rows.getFirst().get("tenant_id");
        String locale = String.valueOf(rows.getFirst().get("locale"));
        Map<String, Object> counts = RequestContext.runAs(tenantId, () -> jdbc.queryForMap("""
                select (select count(*) from tasks where tenant_id = ? and deleted_at is null
                          and status not in ('DONE', 'CANCELLED')) as open,
                       (select count(*) from tasks where tenant_id = ? and deleted_at is null
                          and status not in ('DONE', 'CANCELLED') and due_at < now()) as overdue,
                       (select count(*) from documents where tenant_id = ? and deleted_at is null
                          and processing_status = 'REVIEW_REQUIRED') as review
                """, tenantId, tenantId, tenantId));
        api.sendMessage(chatId, TelegramTexts.t(locale, "status", counts.get("open"), counts.get("overdue"),
                counts.get("review")));
    }

    // ------------------------------------------------------------------ the poller's bookkeeping

    long readOffset() {
        List<String> rows = jdbc.query("select state_value from telegram_state where state_key = 'update_offset'",
                (rs, i) -> rs.getString(1));
        return rows.isEmpty() ? 0 : Long.parseLong(rows.getFirst());
    }

    void writeOffset(long offset) {
        jdbc.update("""
                insert into telegram_state (state_key, state_value) values ('update_offset', ?)
                on conflict (state_key) do update set state_value = excluded.state_value
                """, Long.toString(offset));
    }
}
