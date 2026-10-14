package de.buerokratiecopilot.autopilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * CA-06: an incoming letter that answers one of ours is linked by its headers and lets the case go on; a letter that might
 * belong to several cases, or is only similar, is offered to a person and links nothing by itself.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReplyLinkIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ReplyLinkService replies;

    private record Account(UUID tenantId, UUID userId, Cookie cookie) {
    }

    private Account newTenant() throws Exception {
        String email = "reply-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Reply","accountType":"COMPANY"}
                                """.formatted(email)))
                .andExpect(status().isCreated()).andReturn();
        UUID tenantId = UUID.fromString(JsonPath.read(registration.getResponse().getContentAsString(), "$.tenantId"));
        UUID userId = UUID.fromString(JsonPath.read(registration.getResponse().getContentAsString(), "$.userId"));
        jdbc.update("update users set status = 'ACTIVE', email_verified_at = now() where id = ?", userId);
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"EinSicheresPasswort-2026!\"}".formatted(email)))
                .andExpect(status().isOk()).andReturn();
        return new Account(tenantId, userId, login.getResponse().getCookie("bc_session"));
    }

    private UUID newCase(Account a, String reference) {
        return RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject("""
                insert into cases (id, tenant_id, reference, title, status, priority, opened_at, owner_user_id, responsible_user_id)
                values (gen_random_uuid(), ?, ?, 'Widerspruch', 'IN_PROGRESS', 'NORMAL', now(), ?, ?) returning id
                """, UUID.class, a.tenantId(), reference, a.userId(), a.userId()));
    }

    private UUID sentLetter(Account a, UUID caseId, String providerId) {
        return RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject("""
                insert into outbound_messages (id, tenant_id, case_id, provider_message_id) values (gen_random_uuid(), ?, ?, ?) returning id
                """, UUID.class, a.tenantId(), caseId, providerId));
    }

    private UUID incoming(Account a, String subject, String inReplyTo, String from) {
        return RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject("""
                insert into mail_messages (id, tenant_id, direction, subject, from_address, in_reply_to, received_at, processing_status)
                values (gen_random_uuid(), ?, 'INBOUND', ?, ?, ?, now(), 'RECEIVED') returning id
                """, UUID.class, a.tenantId(), subject, from, inReplyTo));
    }

    private MvcResult send(Account a, MockHttpServletRequestBuilder builder, String content) throws Exception {
        return mockMvc.perform(builder.with(csrf()).cookie(a.cookie()).contentType(MediaType.APPLICATION_JSON).content(content)).andReturn();
    }

    @Test
    @DisplayName("a reply to a letter of the case is linked by its headers; the waiting step ends and the criterion is met")
    void replyByHeaders() throws Exception {
        Account a = newTenant();
        UUID caseId = newCase(a, "R-100");
        UUID letter = sentLetter(a, caseId, "<abc123@copilot.example>");
        send(a, put("/api/v1/cases/" + caseId + "/plan"), """
                {"steps":[{"key":"entwurf","action":"DRAFT_LETTER","title":"Entwurf"},
                          {"key":"freigabe","action":"REQUEST_APPROVAL","title":"Freigabe","dependsOn":["entwurf"]},
                          {"key":"senden","action":"SEND_LETTER","title":"Senden","dependsOn":["freigabe"]},
                          {"key":"warten","action":"WAIT_FOR_REPLY","title":"Auf Antwort warten","dependsOn":["senden"]}]}
                """);
        for (String step : new String[] { "entwurf", "freigabe", "senden" }) {
            send(a, post("/api/v1/cases/" + caseId + "/plan/steps/" + step + "/start"), "{}");
            send(a, post("/api/v1/cases/" + caseId + "/plan/steps/" + step + "/complete"), "{}");
        }
        send(a, post("/api/v1/cases/" + caseId + "/criteria"), "{\"kind\":\"REPLY_RECEIVED\",\"description\":\"Antwort liegt vor\"}");
        UUID mail = incoming(a, "AW: Widerspruch", "<abc123@copilot.example>", "Anbieter <service@anbieter.example>");

        ReplyLinkService.Result result = RequestContext.runAs(a.tenantId(), () -> replies.link(a.tenantId(), mail));

        assertThat(result.status()).isEqualTo("LINKED");
        assertThat(result.caseId()).isEqualTo(caseId);
        assertThat(RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject(
                "select status from workflow_run_steps where step_key = 'warten' and tenant_id = ?", String.class, a.tenantId())))
                .isEqualTo("SUCCEEDED");
        String workspace = mockMvc.perform(get("/api/v1/cases/" + caseId + "/workspace").cookie(a.cookie())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((Object) JsonPath.read(workspace, "$.criteria[0].status")).isEqualTo("MET");
        assertThat(RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject(
                "select count(*) from case_events where case_id = ? and event_type in ('CORRESPONDENCE_RECEIVED', 'REPLY_LINKED')",
                Integer.class, caseId))).isEqualTo(2);

        // Linking it again changes nothing.
        assertThat(RequestContext.runAs(a.tenantId(), () -> replies.link(a.tenantId(), mail)).status()).isEqualTo("LINKED");
        assertThat(RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject(
                "select count(*) from case_events where case_id = ? and event_type = 'REPLY_LINKED'", Integer.class, caseId))).isEqualTo(1);
        assertThat(letter).isNotNull();
    }

    @Test
    @DisplayName("a reply that could belong to two cases is only suggested; a person decides, and only then does the case go on")
    void ambiguousIsSuggested() throws Exception {
        Account a = newTenant();
        UUID first = newCase(a, "R-200");
        UUID second = newCase(a, "R-201");
        sentLetter(a, first, "<shared@copilot.example>");
        sentLetter(a, second, "<shared@copilot.example>");
        send(a, post("/api/v1/cases/" + second + "/criteria"), "{\"kind\":\"REPLY_RECEIVED\",\"description\":\"Antwort liegt vor\"}");
        UUID mail = incoming(a, "AW: Ihr Schreiben", "<shared@copilot.example>", "service@anbieter.example");

        ReplyLinkService.Result result = RequestContext.runAs(a.tenantId(), () -> replies.link(a.tenantId(), mail));
        assertThat(result.status()).isEqualTo("SUGGESTED");
        assertThat(result.candidates()).extracting(ReplyLinkService.Candidate::caseId).containsExactlyInAnyOrder(first, second);
        assertThat(RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject("select case_id from mail_messages where id = ?", UUID.class, mail))).as("nothing is linked on a guess").isNull();
        assertThat(JsonPath.<String>read(mockMvc.perform(get("/api/v1/mail/messages/" + mail + "/case-candidates").cookie(a.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.case_link_status")).isEqualTo("SUGGESTED");

        assertThat(send(a, post("/api/v1/cases/" + second + "/replies/" + mail + "/confirm"), "{}").getResponse().getStatus()).isEqualTo(200);
        assertThat(RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject("select case_id from mail_messages where id = ?", UUID.class, mail))).isEqualTo(second);
        assertThat(RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject(
                "select status from case_completion_criteria where case_id = ?", String.class, second)))
                .as("the criterion is evaluated when the case is looked at").isEqualTo("OPEN");
        String workspace = mockMvc.perform(get("/api/v1/cases/" + second + "/workspace").cookie(a.cookie())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((Object) JsonPath.read(workspace, "$.criteria[0].status")).isEqualTo("MET");
    }

    @Test
    @DisplayName("a letter with only a weak sign (the case reference in the subject) is suggested, never linked; a letter with no sign links to nothing")
    void weakAndNoSign() throws Exception {
        Account a = newTenant();
        UUID caseId = newCase(a, "R-300");
        UUID weak = incoming(a, "Zu Ihrem Vorgang R-300", null, "jemand@anbieter.example");
        UUID none = incoming(a, "Newsletter Oktober", null, "news@shop.example");

        ReplyLinkService.Result weakResult = RequestContext.runAs(a.tenantId(), () -> replies.link(a.tenantId(), weak));
        assertThat(weakResult.status()).isEqualTo("SUGGESTED");
        assertThat(weakResult.candidates()).singleElement().satisfies(c -> {
            assertThat(c.caseId()).isEqualTo(caseId);
            assertThat(c.strong()).isFalse();
        });
        assertThat(RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject("select case_id from mail_messages where id = ?", UUID.class, weak))).isNull();
        assertThat(RequestContext.runAs(a.tenantId(), () -> replies.link(a.tenantId(), none)).status()).isEqualTo("NONE");
    }

    @Test
    @DisplayName("another workspace's letters never count: the same header in another tenant finds nothing, and the link cannot be confirmed across")
    void isolation() throws Exception {
        Account a = newTenant();
        Account b = newTenant();
        UUID caseOfA = newCase(a, "R-400");
        sentLetter(a, caseOfA, "<only-a@copilot.example>");
        UUID mailOfB = incoming(b, "AW: Brief", "<only-a@copilot.example>", "x@y.example");

        assertThat(RequestContext.runAs(b.tenantId(), () -> replies.link(b.tenantId(), mailOfB)).status()).isEqualTo("NONE");
        assertThat(send(b, post("/api/v1/cases/" + caseOfA + "/replies/" + mailOfB + "/confirm"), "{}").getResponse().getStatus()).isEqualTo(404);
    }
}
