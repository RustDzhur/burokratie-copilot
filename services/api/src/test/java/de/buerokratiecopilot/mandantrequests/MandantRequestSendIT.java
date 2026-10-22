package de.buerokratiecopilot.mandantrequests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

import jakarta.mail.internet.MimeMessage;
import jakarta.servlet.http.Cookie;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.support.TenantContextExtension;

/**
 * Sending a client request through a real SMTP server (Kanzlei edition, §5.7).
 *
 * <p>The server is GreenMail in the test process, not a mock: the acceptance is about the message that
 * really left the application, and a mocked sender would let the platform claim anything. The test also
 * checks the two recorded facts — {@code SENT} with {@code sent_at}, and an audit entry — because those
 * are what the office relies on when the client says the letter never arrived.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(TenantContextExtension.class)
class MandantRequestSendIT {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP);

    @DynamicPropertySource
    static void mailProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", () -> "127.0.0.1");
        registry.add("spring.mail.port", () -> ServerSetupTest.SMTP.getPort());
        registry.add("spring.mail.properties.mail.smtp.auth", () -> "false");
        registry.add("spring.mail.properties.mail.smtp.starttls.enable", () -> "false");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private record Account(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private Account register() throws Exception {
        String email = "send-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Kanzlei Versand","accountType":"COMPANY"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID tenantId = UUID.fromString(JsonPath.read(body(registration), "$.tenantId"));
        UUID userId = UUID.fromString(JsonPath.read(body(registration), "$.userId"));
        RequestContext.runAs(tenantId, () -> jdbc.update("""
                update users set status = 'ACTIVE', email_verified_at = now() where id = ?
                """, userId));
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn();
        return new Account(login.getResponse().getCookie("bc_session"), tenantId, userId);
    }

    private static String body(MvcResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private UUID mandant(Account account, String email) {
        return RequestContext.runAs(account.tenantId(), () -> jdbc.queryForObject("""
                insert into contacts (tenant_id, contact_type, display_name, email)
                values (?, 'ORGANIZATION', 'Kartenhaus GmbH', ?)
                returning id
                """, UUID.class, account.tenantId(), email));
    }

    @Test
    @DisplayName("a configured mail provider receives the letter and the request becomes SENT")
    void sendsThroughTheConfiguredProvider() throws Exception {
        Account account = register();
        String recipient = "buchhaltung-" + UUID.randomUUID().toString().substring(0, 6) + "@kartenhaus.example";
        UUID mandantId = mandant(account, recipient);

        MvcResult created = mockMvc.perform(post("/api/v1/mandant-requests").with(csrf())
                        .cookie(account.cookie()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mandantId":"%s","missing":["AMOUNT","DATE"]}
                                """.formatted(mandantId)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID id = UUID.fromString(JsonPath.read(body(created), "$.id"));
        String subject = JsonPath.read(body(created), "$.subject");

        // Verification mails from the registration are not part of what this test observes.
        greenMail.purgeEmailFromAllMailboxes();

        mockMvc.perform(post("/api/v1/mandant-requests/{id}/send", id).with(csrf())
                        .cookie(account.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.sentAt").isNotEmpty());

        assertThat(greenMail.waitForIncomingEmail(5_000, 1)).as("the letter reached the SMTP server").isTrue();
        MimeMessage[] received = greenMail.getReceivedMessages();
        assertThat(received).hasSize(1);
        assertThat(received[0].getSubject()).isEqualTo(subject);
        assertThat(Arrays.stream(received[0].getAllRecipients()).map(Object::toString))
                .containsExactly(recipient);
        assertThat(String.valueOf(received[0].getContent())).contains("Sehr geehrte Damen und Herren")
                .contains("fehlt uns");

        var row = RequestContext.runAs(account.tenantId(), () -> jdbc.queryForMap("""
                select status, sent_at from mandant_requests where id = ?
                """, id));
        assertThat(row.get("status")).isEqualTo("SENT");
        assertThat(row.get("sent_at")).isNotNull();

        Integer audited = RequestContext.runAs(account.tenantId(), () -> jdbc.queryForObject("""
                select count(*) from audit_events
                 where entity_id = ? and action = 'MANDANT_REQUEST_SENT'
                """, Integer.class, id));
        assertThat(audited).isEqualTo(1);
    }
}
