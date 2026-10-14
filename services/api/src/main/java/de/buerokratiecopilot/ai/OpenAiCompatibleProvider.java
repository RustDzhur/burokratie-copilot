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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.shared.config.BurokratieProperties;

/**
 * Talks to any server that speaks the OpenAI chat protocol: OpenAI, DeepSeek, Ollama, vLLM, or a gateway of your own
 * that is reached by an IP address.
 *
 * <p>The key, the address and the model come from the server's configuration and stay here: the request the
 * orchestrator builds carries only what is asked. Nothing from the provider's answer is logged.</p>
 *
 * <p>The input of a request is either plain text (one user message) or, when it starts with {@code {"messages"},
 * a JSON object {@code {"messages":[{"role":"system|user|assistant","content":"..."}]}}.</p>
 *
 * <p>The provider reports no cost: prices differ per model and gateway, so a cost that looked exact would be
 * invented. The monthly budget in euro therefore only counts what a provider reports; the daily limit per workspace
 * ({@code BC_AI_GUIDE_DAILY_LIMIT}) is what caps the use of the assistant.</p>
 */
@Component
public class OpenAiCompatibleProvider implements AiProvider {

    public static final String NAME = "openai-compatible";
    private static final String DEFAULT_MODEL = "gpt-4o-mini";

    private final BurokratieProperties.Ai config;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public OpenAiCompatibleProvider(BurokratieProperties properties, ObjectMapper json) {
        this.config = properties.getAi();
        this.json = json;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean isAvailable() {
        return config.isEnabled() && NAME.equalsIgnoreCase(config.getProvider()) && !config.getBaseUrl().isBlank()
                && !config.getApiKey().isBlank();
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
            boolean withImages = request.images() != null && !request.images().isEmpty();
            body.put("messages", withImages ? visionMessages(request) : messagesOf(request.input()));
            // Reading a page is extraction, not conversation: no invention, and room for a whole answer.
            body.put("temperature", withImages ? 0.0 : 0.3);
            body.put("max_tokens", withImages ? 1200 : 700);
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(endpoint()))
                    .timeout(Duration.ofSeconds(Math.max(5, request.timeoutSeconds())))
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 401 || status == 403) {
                throw new ProviderUnavailableException("AUTH_FAILED", "The provider refused the key", null);
            }
            if (status == 429) {
                throw new ProviderUnavailableException("RATE_LIMITED", "The provider is rate limiting", null);
            }
            if (status / 100 != 2) {
                throw new ProviderUnavailableException("UPSTREAM_ERROR", "The provider answered " + status, null);
            }
            JsonNode root = json.readTree(response.body());
            String text = root.path("choices").path(0).path("message").path("content").asText("");
            if (text.isBlank()) {
                throw new ProviderUnavailableException("EMPTY_ANSWER", "The provider sent an empty answer", null);
            }
            JsonNode usage = root.path("usage");
            int tokensIn = usage.path("prompt_tokens").asInt(0);
            int tokensOut = usage.path("completion_tokens").asInt(0);
            return new Completion(text.trim(), Map.of(), tokensIn, tokensOut, config.costOf(tokensIn, tokensOut), "EUR",
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
        String base = config.getBaseUrl().trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/chat/completions";
    }

    /** One user message: the instruction, then each page as a data URL ({@code data:image/jpeg;base64,...}). */
    private List<Map<String, Object>> visionMessages(Request request) {
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(Map.of("type", "text", "text", request.input() == null ? "" : request.input()));
        for (String image : request.images()) {
            parts.add(Map.of("type", "image_url", "image_url", Map.of("url", image)));
        }
        return List.of(Map.of("role", "user", "content", parts));
    }

    private List<Map<String, String>> messagesOf(String input) throws IOException {
        if (input != null && input.stripLeading().startsWith("{\"messages\"")) {
            List<Map<String, String>> messages = new ArrayList<>();
            for (JsonNode node : json.readTree(input).path("messages")) {
                messages.add(Map.of("role", node.path("role").asText("user"), "content", node.path("content").asText("")));
            }
            return messages;
        }
        return List.of(Map.of("role", "user", "content", input == null ? "" : input));
    }
}
