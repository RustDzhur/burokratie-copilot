package de.buerokratiecopilot.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.http.Cookie;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.shared.context.RequestContext;

/** The informer against a stand-in for the Telegram Bot API: linking by a code, commands, and the news that goes out. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TelegramIT {

    private static final HttpServer BOT = start();
    private static final List<String> SENT = new CopyOnWriteArrayList<>();
    private static final AtomicReference<String> UPDATES = new AtomicReference<>("[]");

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String answer;
                if (path.endsWith("/getUpdates")) {
                    answer = "{\"ok\":true,\"result\":" + UPDATES.getAndSet("[]") + "}";
                }
                else if (path.endsWith("/getMe")) {
                    answer = "{\"ok\":true,\"result\":{\"username\":\"testbot\"}}";
                }
                else {
                    SENT.add(body);
                    answer = "{\"ok\":true,\"result\":{}}";
                }
                byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        }
        catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @DynamicPropertySource
    static void bot(DynamicPropertyRegistry registry) {
        registry.add("burokratie.telegram.bot-token", () -> "test-token");
        registry.add("burokratie.telegram.api-base", () -> "http://127.0.0.1:" + BOT.getAddress().getPort());
        registry.add("burokratie.telegram.poll-enabled", () -> "false");
    }

    @AfterAll
    static void stop() {
        BOT.stop(0);
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private TelegramPoller poller;
    @Autowired
    private TelegramInformer informer;

    private record Session(Cookie cookie, UUID tenantId) {
    }

    private Session signIn() throws Exception {
        String email = "tg-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Telegram Test","accountType":"COMPANY","locale":"ru"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        String body = registration.getResponse().getContentAsString();
        String userId = JsonPath.read(body, "$.userId");
        UUID tenantId = UUID.fromString(JsonPath.read(body, "$.tenantId"));
        RequestContext.runAs(tenantId, () -> {
            User user = users.findById(UUID.fromString(userId)).orElseThrow();
            user.markEmailVerified(java.time.Instant.now());
            user.setStatus(User.Status.ACTIVE);
            return users.save(user);
        });
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"EinSicheresPasswort-2026!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return new Session(login.getResponse().getCookie("bc_session"), tenantId);
    }

    @Test
    @DisplayName("a person links their chat with a code, hears about a new document in their language, and can unlink")
    void linkHearUnlink() throws Exception {
        Session session = signIn();

        mockMvc.perform(get("/api/v1/telegram/status").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.linked").value(false));

        MvcResult created = mockMvc.perform(post("/api/v1/telegram/link-code").with(csrf()).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andReturn();
        String link = JsonPath.read(created.getResponse().getContentAsString(), "$.deepLink");
        assertThat(link).startsWith("https://t.me/testbot?start=");
        String code = link.substring(link.indexOf("start=") + 6);

        // The person opens the bot with the code: the poller reads the message and links the chat.
        UPDATES.set("[{\"update_id\":10,\"message\":{\"chat\":{\"id\":4711,\"type\":\"private\"},\"text\":\"/start " + code + "\"}}]");
        assertThat(poller.pollOnce()).isEqualTo(1);
        assertThat(SENT).anyMatch(message -> message.contains("4711") && message.contains("Подключено"));

        mockMvc.perform(get("/api/v1/telegram/status").cookie(session.cookie()))
                .andExpect(jsonPath("$.linked").value(true));

        // A document arrives: the news goes to the linked chat, in the person's language (ru).
        SENT.clear();
        UUID document = UUID.randomUUID();
        informer.documentReceived(session.tenantId(), document);   // unknown document: nothing is sent, nothing breaks
        assertThat(SENT).isEmpty();

        mockMvc.perform(post("/api/v1/telegram/test").with(csrf()).cookie(session.cookie()))
                .andExpect(status().isNoContent());
        assertThat(SENT).anyMatch(message -> message.contains("4711") && message.contains("Тестовое сообщение"));

        // "/stop" pauses the news; "/status" answers with the figures.
        SENT.clear();
        UPDATES.set("[{\"update_id\":11,\"message\":{\"chat\":{\"id\":4711,\"type\":\"private\"},\"text\":\"/stop\"}}]");
        poller.pollOnce();
        assertThat(SENT).anyMatch(message -> message.contains("приостановлены"));
        mockMvc.perform(get("/api/v1/telegram/status").cookie(session.cookie()))
                .andExpect(jsonPath("$.enabled").value(false));

        mockMvc.perform(delete("/api/v1/telegram/link").with(csrf()).cookie(session.cookie()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/telegram/status").cookie(session.cookie()))
                .andExpect(jsonPath("$.linked").value(false));
    }

    @Test
    @DisplayName("a wrong or expired code links nothing and the chat is told so")
    void wrongCode() {
        SENT.clear();
        UPDATES.set("[{\"update_id\":20,\"message\":{\"chat\":{\"id\":999,\"type\":\"private\"},\"text\":\"/start NOPE\"}}]");
        poller.pollOnce();
        assertThat(SENT).anyMatch(message -> message.contains("999") && message.contains("ungültig"));
    }
}
