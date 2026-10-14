package de.buerokratiecopilot.ai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.shared.config.BurokratieProperties;

/**
 * Talks to the Anthropic Messages API (Claude), including pages given as pictures.
 *
 * <p>A second provider next to {@link OpenAiCompatibleProvider}, selected by {@code BC_AI_PROVIDER=anthropic}. Only one of
 * the two is available at a time: each reports itself unavailable unless the configured provider is its own. The key,
 * the address (default {@code https://api.anthropic.com}; a regional gateway can be configured) and the model come from
 * the server's configuration and stay here; nothing of the answer is logged.</p>
 *
 * <p>Pictures arrive as data URLs ({@code data:image/jpeg;base64,...}) and are passed as base64 image blocks; the
 * instruction follows them, as the API recommends for documents.</p>
 */
@Component
public class AnthropicProvider implements AiProvider {

    public static final String NAME = "anthropic";
    private static final String DEFAULT_MODEL = "claude-sonnet-5-5";
    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    private static final String API_VERSION = "2023-06-01";

    private final BurokratieProperties.Ai config;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public AnthropicProvider(BurokratieProperties properties, ObjectMapper json) {
        this.config = properties.getAi();
        this.json = json;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String defaultModel() {
        return config.getModel().isBlank() ? DEFAULT_MODEL : config.getModel();
    }

    @Override
    public boolean isAvailable() {
        return config.isEnabled() && NAME.equalsIgnoreCase(config.getProvider()) && !config.getApiKey().isBlank();
    }

    @Override
    public Completion complete(Request request) {
        if (!isAvailable()) {
            throw new ProviderUnavailableException("NOT_CONFIGURED", "The AI provider is not configured", null);
        }
        String model = request.model() != null && !request.model().isBlank() ? request.model()
                : config.getModel().isBlank() ? DEFAULT_MODEL : config.getModel();
        long started = System.currentTimeMillis();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("max_tokens", 1200);
            body.put("temperature", 0.0);
            body.put("messages", List.of(Map.of("role", "user", "content", contentOf(request))));
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(endpoint()))
                    .timeout(Duration.ofSeconds(Math.max(5, request.timeoutSeconds())))
                    .header("x-api-key", config.getApiKey())
                    .header("anthropic-version", API_VERSION)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 401 || status == 403) {
                throw new ProviderUnavailableException("AUTH_FAILED", "The provider refused the key", null);
            }
            if (status == 429 || status == 529) {
                throw new ProviderUnavailableException("RATE_LIMITED", "The provider is rate limiting or overloaded",
                        null);
            }
            if (status / 100 != 2) {
                throw new ProviderUnavailableException("UPSTREAM_ERROR", "The provider answered " + status, null);
            }
            JsonNode root = json.readTree(response.body());
            StringBuilder text = new StringBuilder();
            for (JsonNode block : root.path("content")) {
                if ("text".equals(block.path("type").asText())) {
                    text.append(block.path("text").asText(""));
                }
            }
            if (text.toString().isBlank()) {
                throw new ProviderUnavailableException("EMPTY_ANSWER", "The provider sent an empty answer", null);
            }
            JsonNode usage = root.path("usage");
            int tokensIn = usage.path("input_tokens").asInt(0);
            int tokensOut = usage.path("output_tokens").asInt(0);
            return new Completion(text.toString().trim(), Map.of(), tokensIn, tokensOut,
                    config.costOf(tokensIn, tokensOut), "EUR",
                    System.currentTimeMillis() - started, List.of());
        }
        catch (ProviderUnavailableException ex) {
            throw ex;
        }
        catch (IOException ex) {
            throw new ProviderUnavailableException("CONNECTION_FAILED", "The provider could not be reached", ex);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ProviderUnavailableException("INTERRUPTED", "The request was interrupted", ex);
        }
        catch (IllegalArgumentException ex) {
            throw new ProviderUnavailableException("BAD_CONFIGURATION", "The provider address is not valid", ex);
        }
    }

    private String endpoint() {
        String base = config.getBaseUrl() == null || config.getBaseUrl().isBlank() ? DEFAULT_BASE_URL
                : config.getBaseUrl().trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/v1/messages";
    }

    /** The pictures first, then the instruction. */
    private List<Map<String, Object>> contentOf(Request request) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        if (request.images() != null) {
            for (String image : request.images()) {
                // data:image/jpeg;base64,AAAA...
                int comma = image.indexOf(',');
                String header = comma > 0 ? image.substring(0, comma) : "";
                String mediaType = header.startsWith("data:") && header.contains(";")
                        ? header.substring(5, header.indexOf(';')) : "image/jpeg";
                blocks.add(Map.of("type", "image", "source", Map.of("type", "base64", "media_type", mediaType,
                        "data", comma > 0 ? image.substring(comma + 1) : image)));
            }
        }
        blocks.add(Map.of("type", "text", "text", request.input() == null ? "" : request.input()));
        return blocks;
    }
}
