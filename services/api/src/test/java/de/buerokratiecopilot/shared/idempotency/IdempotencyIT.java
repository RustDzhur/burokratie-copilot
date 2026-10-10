package de.buerokratiecopilot.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * Verifies that unsafe requests can be retried safely (spec §7.4, §16.3), including through the real
 * HTTP stack with a real session and CSRF protection.
 *
 * <p>A small probe endpoint counts how often the operation actually runs; the assertions are about
 * that counter, not about the HTTP status alone — an endpoint that claims to be idempotent while
 * still executing twice would pass a status-only test.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IdempotencyIT {

    /** Minimal tenant scoped write endpoint used as the operation under test. */
    @RestController
    static class ProbeController {

        static final AtomicInteger INVOCATIONS = new AtomicInteger();

        @PostMapping("/api/v1/test/idempotency-probe")
        Map<String, Object> probe(@RequestBody(required = false) Map<String, Object> body) {
            int invocation = INVOCATIONS.incrementAndGet();
            Integer failWith = body == null ? null : (Integer) body.get("failWith");
            if (failWith != null) {
                throw new IllegalStateException("probe failure, status " + failWith);
            }
            return Map.of("invocation", invocation, "received", body == null ? Map.of() : body);
        }
    }

    @TestConfiguration
    static class ControllerConfiguration {

        @org.springframework.context.annotation.Bean
        ProbeController probeController() {
            return new ProbeController();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    private Cookie session;

    @BeforeEach
    void signIn() throws Exception {
        ProbeController.INVOCATIONS.set(0);

        String email = "idem-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Idempotency Test","accountType":"COMPANY"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();

        String userId = com.jayway.jsonpath.JsonPath.read(
                registration.getResponse().getContentAsString(), "$.userId");
        String tenantId = com.jayway.jsonpath.JsonPath.read(
                registration.getResponse().getContentAsString(), "$.tenantId");

        RequestContext.runAs(UUID.fromString(tenantId), () -> {
            User user = users.findById(UUID.fromString(userId)).orElseThrow();
            user.markEmailVerified(java.time.Instant.now());
            user.setStatus(User.Status.ACTIVE);
            return users.save(user);
        });

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn();
        session = login.getResponse().getCookie("bc_session");
    }

    private org.springframework.test.web.servlet.RequestBuilder probe(String idempotencyKey, String body) {
        var request = post("/api/v1/test/idempotency-probe")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (idempotencyKey != null) {
            request.header(IdempotencyFilter.HEADER, idempotencyKey);
        }
        return request;
    }

    @Test
    @DisplayName("a repeated request with the same key returns the stored answer and does not run again")
    void repeatedRequestIsReplayed() throws Exception {
        String key = "invoice-" + UUID.randomUUID();
        String body = "{\"amount\":119.00}";

        MvcResult first = mockMvc.perform(probe(key, body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invocation").value(1))
                .andReturn();

        MvcResult second = mockMvc.perform(probe(key, body))
                .andExpect(status().isOk())
                .andReturn();

        // Compared as parsed JSON, not as raw strings: key ordering is not part of the contract.
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(mapper.readTree(second.getResponse().getContentAsString()))
                .as("the second call must return the very same payload")
                .isEqualTo(mapper.readTree(first.getResponse().getContentAsString()));
        assertThat(second.getResponse().getHeader("Idempotent-Replay")).isEqualTo("true");
        assertThat(ProbeController.INVOCATIONS)
                .as("the operation must have run exactly once")
                .hasValue(1);
    }

    @Test
    @DisplayName("the same key with a different payload is refused instead of replaying the wrong answer")
    void sameKeyWithDifferentPayloadIsRefused() throws Exception {
        String key = "invoice-" + UUID.randomUUID();

        mockMvc.perform(probe(key, "{\"amount\":119.00}"))
                .andExpect(status().isOk());

        mockMvc.perform(probe(key, "{\"amount\":999.00}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("different request")));

        assertThat(ProbeController.INVOCATIONS).hasValue(1);
    }

    @Test
    @DisplayName("without the header every request executes, so idempotency stays opt-in")
    void withoutKeyEveryRequestExecutes() throws Exception {
        mockMvc.perform(probe(null, "{}")).andExpect(status().isOk());
        mockMvc.perform(probe(null, "{}")).andExpect(status().isOk());

        assertThat(ProbeController.INVOCATIONS).hasValue(2);
    }

    @Test
    @DisplayName("a failed attempt releases the key so the client can correct the input and retry")
    void failedAttemptReleasesTheKey() throws Exception {
        String key = "retry-" + UUID.randomUUID();

        mockMvc.perform(probe(key, "{\"failWith\":500}"))
                .andExpect(status().is5xxServerError());

        // The same key must be usable again: nothing was created, so nothing may be replayed.
        mockMvc.perform(probe(key, "{\"amount\":42}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invocation").value(2));
    }

    @Test
    @DisplayName("a malformed key is rejected with a clear error")
    void malformedKeyIsRejected() throws Exception {
        mockMvc.perform(probe("short", "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
