package de.buerokratiecopilot.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.shared.config.BurokratieProperties;

/**
 * The real client, against a stand-in server that speaks the OpenAI chat protocol: what it sends, what it makes of the
 * answer, and how it reports the ways a provider fails.
 */
class OpenAiCompatibleProviderTest {

    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private final AtomicReference<String> seenAuthorization = new AtomicReference<>();
    private final AtomicReference<String> seenBody = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String answer = "";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            seenAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            seenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private OpenAiCompatibleProvider provider(boolean enabled, String key) {
        BurokratieProperties properties = new BurokratieProperties();
        properties.getAi().setEnabled(enabled);
        properties.getAi().setProvider("openai-compatible");
        properties.getAi().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/");
        properties.getAi().setApiKey(key);
        properties.getAi().setModel("deepseek-chat");
        return new OpenAiCompatibleProvider(properties, json);
    }

    private static AiProvider.Request request(String input) {
        return new AiProvider.Request(java.util.UUID.randomUUID(), "CHAT", "test", "hash", input, null, 5);
    }

    @Test
    @DisplayName("sends the key as a bearer token, the configured model and the messages, and returns the answer with its usage")
    void sendsAndReads() throws Exception {
        answer = "{\"choices\":[{\"message\":{\"content\":\" Hallo! \"}}],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":3}}";
        AiProvider.Completion completion = provider(true, "sk-test").complete(request(
                "{\"messages\":[{\"role\":\"system\",\"content\":\"Regeln\"},{\"role\":\"user\",\"content\":\"Frage\"}]}"));

        assertThat(completion.text()).isEqualTo("Hallo!");
        assertThat(completion.inputTokens()).isEqualTo(11);
        assertThat(completion.outputTokens()).isEqualTo(3);
        assertThat(seenAuthorization.get()).isEqualTo("Bearer sk-test");
        JsonNode body = json.readTree(seenBody.get());
        assertThat(body.path("model").asText()).isEqualTo("deepseek-chat");
        assertThat(body.path("messages").path(0).path("role").asText()).isEqualTo("system");
        assertThat(body.path("messages").path(1).path("content").asText()).isEqualTo("Frage");
    }

    @Test
    @DisplayName("plain text input becomes one user message")
    void plainInput() throws Exception {
        answer = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";
        provider(true, "k").complete(request("Eine Frage"));
        JsonNode body = json.readTree(seenBody.get());
        assertThat(body.path("messages")).hasSize(1);
        assertThat(body.path("messages").path(0).path("content").asText()).isEqualTo("Eine Frage");
    }

    @Test
    @DisplayName("is not available while switched off or without a key, and never calls out then")
    void unavailable() {
        assertThat(provider(false, "k").isAvailable()).isFalse();
        assertThat(provider(true, "").isAvailable()).isFalse();
        assertThat(provider(true, "k").isAvailable()).isTrue();
        assertThatThrownBy(() -> provider(false, "k").complete(request("x")))
                .isInstanceOf(AiProvider.ProviderUnavailableException.class);
        assertThat(seenBody.get()).isNull();
    }

    @Test
    @DisplayName("names the way a provider fails, without putting the key or the answer into the message")
    void failures() {
        status = 401;
        answer = "{\"error\":\"bad key sk-test\"}";
        assertThatThrownBy(() -> provider(true, "sk-test").complete(request("x")))
                .isInstanceOfSatisfying(AiProvider.ProviderUnavailableException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("AUTH_FAILED");
                    assertThat(ex.getMessage()).doesNotContain("sk-test");
                });

        status = 429;
        assertThatThrownBy(() -> provider(true, "k").complete(request("x")))
                .isInstanceOfSatisfying(AiProvider.ProviderUnavailableException.class,
                        ex -> assertThat(ex.code()).isEqualTo("RATE_LIMITED"));

        status = 500;
        assertThatThrownBy(() -> provider(true, "k").complete(request("x")))
                .isInstanceOfSatisfying(AiProvider.ProviderUnavailableException.class,
                        ex -> assertThat(ex.code()).isEqualTo("UPSTREAM_ERROR"));

        status = 200;
        answer = "{\"choices\":[]}";
        assertThatThrownBy(() -> provider(true, "k").complete(request("x")))
                .isInstanceOfSatisfying(AiProvider.ProviderUnavailableException.class,
                        ex -> assertThat(ex.code()).isEqualTo("EMPTY_ANSWER"));
    }

    @Test
    @DisplayName("an unreachable server is reported as such")
    void unreachable() {
        OpenAiCompatibleProvider provider = provider(true, "k");
        server.stop(0);
        assertThatThrownBy(() -> provider.complete(request("x")))
                .isInstanceOfSatisfying(AiProvider.ProviderUnavailableException.class,
                        ex -> assertThat(ex.code()).isEqualTo("CONNECTION_FAILED"));
    }
}
