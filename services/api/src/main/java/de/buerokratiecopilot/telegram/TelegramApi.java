package de.buerokratiecopilot.telegram;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The few calls of the Telegram Bot API the informer needs. The bot's token is a secret: it is never logged and never
 * part of an exception message.
 */
@Component
public class TelegramApi {

    /** The bot refused the call: {@code status} is the HTTP status, 403 means the person blocked the bot. */
    public static class TelegramException extends RuntimeException {
        private final int status;

        public TelegramException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private final String token;
    private final String base;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public TelegramApi(@Value("${burokratie.telegram.bot-token:}") String token,
                       @Value("${burokratie.telegram.api-base:https://api.telegram.org}") String base,
                       ObjectMapper json) {
        this.token = token == null ? "" : token.strip();
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.json = json;
    }

    public boolean isConfigured() {
        return !token.isBlank();
    }

    public JsonNode call(String method, Map<String, Object> params) {
        if (!isConfigured()) {
            throw new TelegramException(0, "The Telegram bot is not configured");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/bot" + token + "/" + method))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(params)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode body = json.readTree(response.body());
            if (response.statusCode() / 100 != 2 || !body.path("ok").asBoolean(false)) {
                throw new TelegramException(response.statusCode(),
                        "Telegram " + method + " answered " + response.statusCode() + ": "
                                + body.path("description").asText(""));
            }
            return body.path("result");
        }
        catch (IOException ex) {
            throw new TelegramException(0, "Telegram " + method + " could not be reached");
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new TelegramException(0, "Telegram " + method + " was interrupted");
        }
    }

    public void sendMessage(long chatId, String text) {
        call("sendMessage", Map.of("chat_id", chatId, "text", text, "disable_web_page_preview", true));
    }

    public JsonNode getUpdates(long offset) {
        return call("getUpdates", Map.of("offset", offset, "timeout", 0, "allowed_updates",
                java.util.List.of("message")));
    }

    public String botUsername() {
        return call("getMe", Map.of()).path("username").asText("");
    }
}
