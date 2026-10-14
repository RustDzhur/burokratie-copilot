package de.buerokratiecopilot.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Reads what people write to the bot (the link codes and the commands). Every few seconds one instance of the platform
 * asks Telegram for new messages; the lock keeps the API and the worker from asking at once, which Telegram refuses.
 */
@Component
public class TelegramPoller {

    private static final Logger log = LoggerFactory.getLogger(TelegramPoller.class);

    private final TelegramApi api;
    private final TelegramService service;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final boolean enabled;

    public TelegramPoller(TelegramApi api, TelegramService service, JdbcTemplate jdbc,
                          PlatformTransactionManager transactionManager,
                          @Value("${burokratie.telegram.poll-enabled:true}") boolean enabled) {
        this.api = api;
        this.service = service;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${burokratie.telegram.poll-ms:4000}", initialDelayString = "${burokratie.telegram.poll-initial-ms:20000}")
    public void poll() {
        if (!enabled || !api.isConfigured()) {
            return;
        }
        try {
            tx.executeWithoutResult(status -> {
                Boolean locked = jdbc.queryForObject("select pg_try_advisory_xact_lock(727001)", Boolean.class);
                if (!Boolean.TRUE.equals(locked)) {
                    return;
                }
                pollOnce();
            });
        }
        catch (RuntimeException ex) {
            log.warn("Reading the Telegram bot failed: {}", ex.getMessage());
        }
    }

    /** One round; public for the tests. */
    public int pollOnce() {
        long offset = service.readOffset();
        JsonNode updates = api.getUpdates(offset);
        int handled = 0;
        long next = offset;
        for (JsonNode update : updates) {
            next = Math.max(next, update.path("update_id").asLong() + 1);
            JsonNode message = update.path("message");
            if (message.isMissingNode() || !"private".equals(message.path("chat").path("type").asText())) {
                continue;
            }
            service.handleMessage(message.path("chat").path("id").asLong(), message.path("text").asText(""));
            handled++;
        }
        if (next != offset) {
            service.writeOffset(next);
        }
        return handled;
    }
}
