package de.buerokratiecopilot.mail.inbound.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * Mail collection over HTTP (spec §12).
 *
 * <p><strong>The assertion this test exists for:</strong> a mailbox that was checked and had nothing is not
 * the same as one that could not be checked. There is no IMAP server here, so the honest outcomes are a
 * refusal for a mailbox that is not connected and a failure for one that is — and what must never happen is a
 * successful run of zero, which would leave somebody believing their post had been read.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MailIngestControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    private record Session(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private Session register() throws Exception {
        String email = "ing-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Abruf Test","accountType":"COMPANY"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID tenantId = UUID.fromString(JsonPath.read(
                registration.getResponse().getContentAsString(), "$.tenantId"));
        UUID userId = UUID.fromString(JsonPath.read(
                registration.getResponse().getContentAsString(), "$.userId"));

        RequestContext.runAs(tenantId, () -> {
            User user = users.findById(userId).orElseThrow();
            user.markEmailVerified(java.time.Instant.now());
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

    /** A mailbox connected through the route that exists for it, so the fixture uses no private path. */
    private UUID connectedMailbox(Session session) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/mail/accounts").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"Eingang","emailAddress":"post@example.de","provider":"IMAP",
                                 "imapHost":"imap.invalid","imapPort":993,"imapUsername":"post",
                                 "imapUseSsl":true,"password":"%s"}
                                """.formatted("nicht-echt-" + UUID.randomUUID().toString().substring(0, 8))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(JsonPath.read(
                created.getResponse().getContentAsString(), "$.accountId"));
    }

    // ---------------------------------------------------------------- the distinctions

    @Test
    @DisplayName("a mailbox that is not connected is refused rather than reported as empty")
    void anUnconnectedMailboxIsRefused() throws Exception {
        Session session = register();

        // A successful run of zero would leave somebody believing their post had been read, and a deadline
        // letter sitting in a mailbox nobody looked at is what this prevents.
        mockMvc.perform(post("/api/v1/mail/accounts/{id}/collect", UUID.randomUUID()).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("a mailbox that cannot be reached fails rather than reporting nothing new")
    void anUnreachableMailboxFails() throws Exception {
        Session session = register();
        UUID accountId = connectedMailbox(session);

        // There is no IMAP server at that address. The run must not answer 200 with zeros: examined-nothing
        // and found-nothing are different facts, and reporting the second when the first happened is how a
        // quiet week is confused with a broken configuration.
        MvcResult attempt = mockMvc.perform(
                        post("/api/v1/mail/accounts/{id}/collect", accountId).with(csrf())
                                .cookie(session.cookie())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"limit\":5}"))
                .andReturn();

        org.assertj.core.api.Assertions.assertThat(attempt.getResponse().getStatus())
                .as("an unreachable mailbox must not be reported as a successful run of zero")
                .isNotEqualTo(200);
    }

    @Test
    @DisplayName("a limit below one is refused, because a run that examines nothing says nothing")
    void aLimitBelowOneIsRefused() throws Exception {
        Session session = register();
        UUID accountId = connectedMailbox(session);

        mockMvc.perform(post("/api/v1/mail/accounts/{id}/collect", accountId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limit\":0}"))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- what is refused

    @Test
    @DisplayName("without a session nothing is collected")
    void withoutASessionNothingIsCollected() throws Exception {
        mockMvc.perform(post("/api/v1/mail/accounts/{id}/collect", UUID.randomUUID()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("another account's mailbox is not collected from")
    void anotherAccountsMailboxIsNotCollected() throws Exception {
        Session first = register();
        Session second = register();
        UUID accountId = connectedMailbox(first);

        mockMvc.perform(post("/api/v1/mail/accounts/{id}/collect", accountId).with(csrf())
                        .cookie(second.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("the mail permissions exist and are held, which is what makes this reachable")
    void theMailPermissionsAreHeld() {
        Integer held = jdbc.queryForObject("""
                select count(*) from role_permissions rp
                join roles r on r.id = rp.role_id
                join permissions p on p.id = rp.permission_id
                where p.code in ('mail.configure','mail.connect')
                  and r.tenant_id is null and r.code in ('OWNER','ADMIN')
                """, Integer.class);

        org.assertj.core.api.Assertions.assertThat(held)
                .as("the mail permissions must be granted to OWNER and ADMIN, or this is dead")
                .isGreaterThan(0);
    }
}
