package de.buerokratiecopilot.mail.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * What happens when no application has been registered with Microsoft or Google.
 *
 * <p>This is the normal state of a fresh installation, and the answer has to be an ordinary, explained
 * refusal — not a stack trace and not a redirect into a flow that cannot finish. The route answers
 * {@code 503} with the missing configuration keys, and the providers endpoint says the same thing before a
 * person clicks. Nothing here fails because a secret is absent; that is the point.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "burokratie.mail.oauth.microsoft.client-id=",
        "burokratie.mail.oauth.microsoft.client-secret=",
        "burokratie.mail.oauth.google.client-id=",
        "burokratie.mail.oauth.google.client-secret="
})
class MailOAuthNotConfiguredIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private UserRepository users;

    private record Session(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private Session register() throws Exception {
        String email = "unconfigured-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"OAuth Test","accountType":"COMPANY"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID tenantId = UUID.fromString(JsonPath.read(
                registration.getResponse().getContentAsString(), "$.tenantId"));
        UUID userId = UUID.fromString(JsonPath.read(
                registration.getResponse().getContentAsString(), "$.userId"));
        RequestContext.runAs(tenantId, () -> {
            User user = users.findById(userId).orElseThrow();
            user.markEmailVerified(Instant.now());
            user.setStatus(User.Status.ACTIVE);
            return users.save(user);
        });
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn();
        return new Session(login.getResponse().getCookie("bc_session"), tenantId, userId);
    }

    @Test
    @DisplayName("the providers endpoint says no application is registered, and what is missing")
    void providersSayWhatIsMissing() throws Exception {
        Session session = register();

        mockMvc.perform(get("/api/v1/mail/oauth/providers").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].provider").value("microsoft"))
                .andExpect(jsonPath("$[0].configured").value(false))
                .andExpect(jsonPath("$[0].missingConfiguration").isArray())
                .andExpect(jsonPath("$[1].provider").value("google"))
                .andExpect(jsonPath("$[1].configured").value(false));
    }

    @Test
    @DisplayName("start without a registered application answers a clear 503, not a crash")
    void startWithoutApplicationIsExplained() throws Exception {
        Session session = register();

        MvcResult refused = mockMvc.perform(get("/api/v1/mail/oauth/microsoft/start")
                        .cookie(session.cookie()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("FEATURE_DISABLED"))
                .andExpect(jsonPath("$.details").isArray())
                .andExpect(jsonPath("$.details[0].field").value("client-id"))
                .andReturn();

        assertThat(refused.getResponse().getHeader("Location")).isNull();
        assertThat(refused.getResponse().getCookie("bc_mail_oauth_microsoft")).isNull();
        assertThat(jdbc.queryForObject("select count(*) from mail_accounts where tenant_id = ?",
                Integer.class, session.tenantId())).isZero();
    }

    @Test
    @DisplayName("an unknown provider is a 404 rather than a redirect to nowhere")
    void unknownProviderIsNotFound() throws Exception {
        Session session = register();

        mockMvc.perform(get("/api/v1/mail/oauth/telepathy/start").cookie(session.cookie()))
                .andExpect(status().isNotFound());
    }
}
