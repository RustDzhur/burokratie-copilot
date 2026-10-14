package de.buerokratiecopilot.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import de.buerokratiecopilot.shared.config.BurokratieProperties;

/**
 * The Anthropic client against a stand-in server that speaks the Messages API: what it sends (key header, version,
 * pictures before the instruction), what it makes of the answer and how it names the ways a provider fails. The same for
 * the pictures that the OpenAI-compatible client sends, because reading a scan depends on both.
 */
class AnthropicProviderTest {

    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private final AtomicReference<String> seenKey = new AtomicReference<>();
    private final AtomicReference<String> seenVersion = new AtomicReference<>();
    private final AtomicReference<String> seenBody = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String answer = "";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seenKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            seenVersion.set(exchange.getRequestHeaders().getFirst("anthropic-version"));
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

    private BurokratieProperties properties(String provider, boolean enabled, String key, String path) {
        BurokratieProperties properties = new BurokratieProperties();
        properties.getAi().setEnabled(enabled);
        properties.getAi().setProvider(provider);
        properties.getAi().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + path);
        properties.getAi().setApiKey(key);
        properties.getAi().setModel("test-model");
        return properties;
    }

    private AnthropicProvider anthropic(boolean enabled, String key) {
        return new AnthropicProvider(properties("anthropic", enabled, key, ""), json);
    }

    private static AiProvider.Request withPictures(String instruction) {
        return new AiProvider.Request(UUID.randomUUID(), "DOCUMENT_READ", "v1", "hash", instruction, null, 5,
                List.of("data:image/jpeg;base64,AAAA", "data:image/png;base64,BBBB"));
    }

    @Test
    @DisplayName("sends the key and version headers, the pictures before the instruction, and reads the answer with its usage")
    void sendsPicturesAndReadsAnswer() throws Exception {
        answer = "{\"content\":[{\"type\":\"text\",\"text\":\" {\\\"documentDate\\\":\\\"2025-10-22\\\"} \"}],"
                + "\"usage\":{\"input_tokens\":1500,\"output_tokens\":40}}";

        AiProvider.Completion completion = anthropic(true, "sk-ant-test").complete(withPictures("Lies das Dokument"));

        assertThat(completion.text()).isEqualTo("{\"documentDate\":\"2025-10-22\"}");
        assertThat(completion.inputTokens()).isEqualTo(1500);
        assertThat(completion.outputTokens()).isEqualTo(40);
        assertThat(seenKey.get()).isEqualTo("sk-ant-test");
        assertThat(seenVersion.get()).isEqualTo("2023-06-01");
        JsonNode body = json.readTree(seenBody.get());
        assertThat(body.path("model").asText()).isEqualTo("test-model");
        JsonNode content = body.path("messages").path(0).path("content");
        assertThat(content).hasSize(3);
        assertThat(content.path(0).path("type").asText()).isEqualTo("image");
        assertThat(content.path(0).path("source").path("media_type").asText()).isEqualTo("image/jpeg");
        assertThat(content.path(0).path("source").path("data").asText()).isEqualTo("AAAA");
        assertThat(content.path(1).path("source").path("media_type").asText()).isEqualTo("image/png");
        assertThat(content.path(2).path("type").asText()).isEqualTo("text");
        assertThat(content.path(2).path("text").asText()).isEqualTo("Lies das Dokument");
    }

    @Test
    @DisplayName("is available only for its own provider name, with a key, and never calls out otherwise")
    void availability() {
        assertThat(anthropic(true, "k").isAvailable()).isTrue();
        assertThat(anthropic(false, "k").isAvailable()).isFalse();
        assertThat(anthropic(true, "").isAvailable()).isFalse();
        assertThat(new AnthropicProvider(properties("openai-compatible", true, "k", ""), json).isAvailable()).isFalse();
        assertThatThrownBy(() -> anthropic(false, "k").complete(withPictures("x")))
                .isInstanceOf(AiProvider.ProviderUnavailableException.class);
        assertThat(seenBody.get()).isNull();
    }

    @Test
    @DisplayName("names how a provider fails without the key or the answer in the message")
    void failures() {
        status = 401;
        answer = "{\"error\":\"bad key sk-ant-test\"}";
        assertThatThrownBy(() -> anthropic(true, "sk-ant-test").complete(withPictures("x")))
                .isInstanceOfSatisfying(AiProvider.ProviderUnavailableException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("AUTH_FAILED");
                    assertThat(ex.getMessage()).doesNotContain("sk-ant-test");
                });
        status = 529;
        assertThatThrownBy(() -> anthropic(true, "k").complete(withPictures("x")))
                .isInstanceOfSatisfying(AiProvider.ProviderUnavailableException.class,
                        ex -> assertThat(ex.code()).isEqualTo("RATE_LIMITED"));
        status = 200;
        answer = "{\"content\":[]}";
        assertThatThrownBy(() -> anthropic(true, "k").complete(withPictures("x")))
                .isInstanceOfSatisfying(AiProvider.ProviderUnavailableException.class,
                        ex -> assertThat(ex.code()).isEqualTo("EMPTY_ANSWER"));
    }

    @Test
    @DisplayName("the OpenAI-compatible client sends the pictures as image_url parts of one user message")
    void openAiSendsPictures() throws Exception {
        answer = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";
        new OpenAiCompatibleProvider(properties("openai-compatible", true, "k", "/v1"), json)
                .complete(withPictures("Lies das Dokument"));

        JsonNode parts = json.readTree(seenBody.get()).path("messages").path(0).path("content");
        assertThat(parts).hasSize(3);
        assertThat(parts.path(0).path("type").asText()).isEqualTo("text");
        assertThat(parts.path(1).path("type").asText()).isEqualTo("image_url");
        assertThat(parts.path(1).path("image_url").path("url").asText()).isEqualTo("data:image/jpeg;base64,AAAA");
    }

    @Test
    @DisplayName("the safe metadata of a request counts the pictures and never contains them")
    void metadataHasNoPictures() {
        var metadata = withPictures("geheim").asSafeMetadata();
        assertThat(metadata.get("imageCount")).isEqualTo(2);
        assertThat(metadata.toString()).doesNotContain("AAAA").doesNotContain("geheim");
    }
}
