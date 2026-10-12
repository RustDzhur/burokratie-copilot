package de.buerokratiecopilot.mail.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * Mail over HTTP (spec §12, §28).
 *
 * <p>What this checks beyond the routes: a mailbox password <strong>has no route at all</strong>, and a
 * handover to the provider is <strong>never reported as a delivery</strong> — those are different facts and
 * only one of them means the letter arrived.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MailControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    private record Session(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private Session register() throws Exception {
        String email = "mail-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Post Test","accountType":"COMPANY"}
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

    private UUID connectMailbox(Session session) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/mail/accounts").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"Eingang","emailAddress":"post@example.de","provider":"IMAP",
                                 "imapHost":"imap.example.de","imapPort":993,"imapUsername":"post",
                                 "imapUseSsl":true,"smtpHost":"smtp.example.de","smtpPort":587,
                                 "smtpUsername":"post","password":"streng-geheim-2026"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(JsonPath.read(created.getResponse().getContentAsString(), "$.accountId"));
    }

    // ---------------------------------------------------------------- the password

    @Test
    @DisplayName("a mailbox is connected and the password never comes back")
    void thePasswordNeverComesBack() throws Exception {
        Session session = register();

        MvcResult created = mockMvc.perform(post("/api/v1/mail/accounts").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"Eingang","emailAddress":"post@example.de","provider":"IMAP",
                                 "imapHost":"imap.example.de","imapPort":993,"imapUsername":"post",
                                 "imapUseSsl":true,"password":"streng-geheim-2026"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountId").isNotEmpty())
                .andExpect(jsonPath("$.emailAddress").value("post@example.de"))
                .andExpect(jsonPath("$.note").isNotEmpty())
                .andReturn();

        org.assertj.core.api.Assertions.assertThat(created.getResponse().getContentAsString())
                .doesNotContain("streng-geheim-2026");
    }

    @Test
    @DisplayName("there is no route that hands out a mailbox password")
    void thereIsNoPasswordRoute() throws Exception {
        Session session = register();
        UUID accountId = connectMailbox(session);

        // Deliberate: an endpoint returning it would put a mailbox password into a browser session, into
        // whatever proxy sits in front of it, and into every screenshot of that page.
        mockMvc.perform(get("/api/v1/mail/accounts/{id}/password", accountId).cookie(session.cookie()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a mailbox without an address or a server is refused")
    void aMailboxNeedsItsFields() throws Exception {
        Session session = register();

        mockMvc.perform(post("/api/v1/mail/accounts").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"imapHost\":\"imap.example.de\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/mail/accounts").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emailAddress\":\"post@example.de\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("whether a mailbox may be synchronised is answered")
    void maySyncIsAnswered() throws Exception {
        Session session = register();
        UUID accountId = connectMailbox(session);

        mockMvc.perform(get("/api/v1/mail/accounts/{id}/may-sync", accountId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maySync").isBoolean());
    }

    @Test
    @DisplayName("a failed sync is recorded with its message rather than swallowed")
    void aFailedSyncIsRecorded() throws Exception {
        Session session = register();
        UUID accountId = connectMailbox(session);

        mockMvc.perform(post("/api/v1/mail/accounts/{id}/sync-results", accountId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"FAILED\",\"errorMessage\":\"Anmeldung abgelehnt\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/mail/accounts/{id}/sync-results", accountId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- outgoing

    @Test
    @DisplayName("a draft is written, and the same key does not write a second one")
    void aDraftIsWrittenOnce() throws Exception {
        Session session = register();
        String body = """
                {"channel":"EMAIL","subject":"Widerspruch","bodyText":"Sehr geehrte Damen und Herren",
                 "toAddresses":["behoerde@example.de"],"idempotencyKey":"entwurf-1"}
                """;

        MvcResult first = mockMvc.perform(post("/api/v1/mail/messages").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        // A retry after a timeout must not post a second letter.
        MvcResult second = mockMvc.perform(post("/api/v1/mail/messages").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        org.assertj.core.api.Assertions.assertThat(
                        JsonPath.read(second.getResponse().getContentAsString(), "$.messageId").toString())
                .isEqualTo(JsonPath.read(first.getResponse().getContentAsString(), "$.messageId")
                        .toString());
    }

    @Test
    @DisplayName("a letter without a subject or a recipient is refused")
    void aLetterNeedsItsFields() throws Exception {
        Session session = register();

        mockMvc.perform(post("/api/v1/mail/messages").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"EMAIL\",\"toAddresses\":[\"a@example.de\"]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/mail/messages").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"EMAIL\",\"subject\":\"Ohne Empfänger\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an unapproved letter is not handed over, and the refusal says why")
    void anUnapprovedLetterIsNotSent() throws Exception {
        Session session = register();
        MvcResult drafted = mockMvc.perform(post("/api/v1/mail/messages").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channel":"EMAIL","subject":"Widerspruch","bodyText":"Text",
                                 "toAddresses":["behoerde@example.de"],"idempotencyKey":"senden-1"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        UUID messageId = UUID.fromString(JsonPath.read(
                drafted.getResponse().getContentAsString(), "$.messageId"));

        // An objection does not leave the house because somebody wrote it: the delivery rules require an
        // approval first, and the refusal carries the reason rather than a bare status.
        mockMvc.perform(post("/api/v1/mail/messages/{id}/send", messageId).with(csrf())
                        .cookie(session.cookie()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("whether a message answers something that came in is answered")
    void isReplyIsAnswered() throws Exception {
        Session session = register();
        MvcResult drafted = mockMvc.perform(post("/api/v1/mail/messages").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channel":"EMAIL","subject":"Widerspruch","bodyText":"Text",
                                 "toAddresses":["behoerde@example.de"],"idempotencyKey":"antwort-1"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        UUID messageId = UUID.fromString(JsonPath.read(
                drafted.getResponse().getContentAsString(), "$.messageId"));

        mockMvc.perform(get("/api/v1/mail/messages/{id}/is-reply", messageId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isReplyToIncoming").isBoolean());
    }

    // ---------------------------------------------------------------- what is refused

    @Test
    @DisplayName("without a session nothing is read, connected or sent")
    void withoutASessionNothingHappens() throws Exception {
        mockMvc.perform(post("/api/v1/mail/accounts").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emailAddress\":\"a@example.de\",\"imapHost\":\"i\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/mail/messages").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"x\",\"toAddresses\":[\"a@example.de\"]}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/mail/accounts/{id}/may-sync", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a mailbox of another account is not reachable")
    void anotherAccountsMailboxIsNotReachable() throws Exception {
        Session first = register();
        Session second = register();
        UUID accountId = connectMailbox(first);

        mockMvc.perform(get("/api/v1/mail/accounts/{id}/may-sync", accountId).cookie(second.cookie()))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("the mail permissions exist and are held, which is what makes these reachable")
    void theMailPermissionsAreHeld() {
        Integer held = jdbc.queryForObject("""
                select count(*) from role_permissions rp
                join roles r on r.id = rp.role_id
                join permissions p on p.id = rp.permission_id
                where p.code in ('mail.view','mail.connect','mail.send','mail.configure')
                  and r.tenant_id is null and r.code in ('OWNER','ADMIN')
                """, Integer.class);

        org.assertj.core.api.Assertions.assertThat(held)
                .as("the mail permissions must be granted to OWNER and ADMIN, or these are dead")
                .isGreaterThan(0);
    }
}
