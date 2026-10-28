package de.buerokratiecopilot.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import de.buerokratiecopilot.autopilot.ReplyLinkService;
import de.buerokratiecopilot.radar.RadarEngine;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.support.TestDocuments;
import de.buerokratiecopilot.workflow.approval.ApprovalService;

/**
 * HD-E2E-01: "an unexpected increase of an invoice", from the second invoice to the closed case.
 *
 * <p>Nothing leaves the building: the provider's acceptance and delivery are recorded the way a provider would report them,
 * and the answer of the other side arrives as a stored incoming letter.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UnexpectedInvoiceIncreaseIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RadarEngine radar;
    @Autowired
    private ApprovalService approvals;
    @Autowired
    private ReplyLinkService replies;

    private record Account(UUID tenantId, UUID userId, Cookie cookie) {
    }

    private Account newTenant() throws Exception {
        String email = "e2e-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult r = mockMvc.perform(post("/api/v1/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"EinSicheresPasswort-2026!\",\"displayName\":\"E2E\",\"accountType\":\"COMPANY\"}".formatted(email)))
                .andExpect(status().isCreated()).andReturn();
        UUID tenantId = UUID.fromString(JsonPath.read(r.getResponse().getContentAsString(), "$.tenantId"));
        UUID userId = UUID.fromString(JsonPath.read(r.getResponse().getContentAsString(), "$.userId"));
        jdbc.update("update users set status = 'ACTIVE', email_verified_at = now() where id = ?", userId);
        Cookie c = mockMvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"EinSicheresPasswort-2026!\"}".formatted(email))).andExpect(status().isOk())
                .andReturn().getResponse().getCookie("bc_session");
        return new Account(tenantId, userId, c);
    }

    private UUID upload(Account a, String text) throws Exception {
        MvcResult r = mockMvc.perform(multipart("/api/v1/documents")
                .file(new MockMultipartFile("file", "Rechnung.pdf", "application/pdf", TestDocuments.pdf(text))).with(csrf()).cookie(a.cookie()))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(JsonPath.read(r.getResponse().getContentAsString(), "$.document.id"));
    }

    private MvcResult send(Account a, MockHttpServletRequestBuilder b, String body) throws Exception {
        return mockMvc.perform(b.with(csrf()).cookie(a.cookie()).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private UUID invoice(Account a, UUID contact, UUID document, UUID contract, String number, String gross, String date) {
        return RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject("""
                insert into invoices (id, tenant_id, direction, contact_id, document_id, contract_id, invoice_number, gross_amount, issue_date)
                values (gen_random_uuid(), ?, 'INCOMING', ?, ?, ?, ?, ?::numeric, ?::date) returning id
                """, UUID.class, a.tenantId(), contact, document, contract, number, gross, date));
    }

    @Test
    @DisplayName("increase found -> finding with both invoices and the contract price -> case -> plan -> approval bound to the letter -> reply linked -> case completed")
    void wholeChain() throws Exception {
        Account a = newTenant();
        UUID contact = RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject("""
                insert into contacts (id, tenant_id, contact_type, display_name, organization_name, is_supplier)
                values (gen_random_uuid(), ?, 'ORGANIZATION', 'Stadtwerke', 'Stadtwerke', true) returning id
                """, UUID.class, a.tenantId()));
        UUID contract = RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject("""
                insert into contracts (id, tenant_id, title, contract_type, status, cost_amount, cost_interval, counterparty_contact_id)
                values (gen_random_uuid(), ?, 'Strom', 'ENERGY', 'ACTIVE', 100.00, 'MONTHLY', ?) returning id
                """, UUID.class, a.tenantId(), contact));
        UUID d1 = upload(a, "Rechnung September");
        UUID d2 = upload(a, "Rechnung Oktober");
        invoice(a, contact, d1, contract, "S-1", "100.00", "2026-09-10");
        invoice(a, contact, d2, contract, "S-2", "140.00", "2026-10-10");

        // 1-2. The increase is found; a redelivered event finds the same finding and tells nobody twice.
        RequestContext.runAs(a.tenantId(), () -> radar.evaluateDocument(a.tenantId(), d2));
        RequestContext.runAs(a.tenantId(), () -> radar.evaluateDocument(a.tenantId(), d2));
        String findings = mockMvc.perform(get("/api/v1/radar/findings").cookie(a.cookie())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((Integer) JsonPath.read(findings, "$.total")).isEqualTo(1);
        String findingId = JsonPath.read(findings, "$.items[0].id");
        String detail = mockMvc.perform(get("/api/v1/radar/findings/" + findingId).cookie(a.cookie())).andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(detail, "$.description")).contains("140.00").contains("100.00").contains("Der Vertrag nennt 100.00");
        assertThat((List<?>) JsonPath.read(detail, "$.evidence")).hasSize(2);
        assertThat(RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject(
                "select count(*) from notifications where tenant_id = ? and category = 'RADAR'", Integer.class, a.tenantId()))).isEqualTo(1);

        // 3. A person confirms the finding and opens a case from it.
        assertThat(send(a, post("/api/v1/radar/findings/" + findingId + "/confirm"), "{}").getResponse().getStatus()).isEqualTo(200);
        String withCase = send(a, post("/api/v1/radar/findings/" + findingId + "/create-case"), "{}").getResponse().getContentAsString();
        UUID caseId = UUID.fromString(JsonPath.read(withCase, "$.related_case_id"));

        // 4. Goal, criteria and plan.
        send(a, put("/api/v1/cases/" + caseId + "/goal"), "{\"goal\":\"Erhöhung klären\",\"nextAction\":\"Widerspruch entwerfen\"}");
        UUID letter = RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject("""
                insert into outbound_messages (id, tenant_id, case_id, subject, body_text, to_addresses, from_address)
                values (gen_random_uuid(), ?, ?, 'Widerspruch Rechnung S-2', 'Der Betrag weicht vom Vertrag ab.', '["service@stadtwerke.example"]'::jsonb, 'wir@example.de')
                returning id
                """, UUID.class, a.tenantId(), caseId));
        send(a, post("/api/v1/cases/" + caseId + "/criteria"), "{\"kind\":\"MESSAGE_DELIVERED\",\"description\":\"Widerspruch zugestellt\",\"outboundMessageId\":\"%s\"}".formatted(letter));
        send(a, post("/api/v1/cases/" + caseId + "/criteria"), "{\"kind\":\"REPLY_RECEIVED\",\"description\":\"Antwort liegt vor\",\"outboundMessageId\":\"%s\"}".formatted(letter));
        send(a, put("/api/v1/cases/" + caseId + "/plan"), """
                {"steps":[{"key":"analyse","action":"COMPARE_WITH_PREVIOUS","title":"Mit der letzten Rechnung vergleichen"},
                          {"key":"entwurf","action":"DRAFT_LETTER","title":"Widerspruch entwerfen","dependsOn":["analyse"]},
                          {"key":"freigabe","action":"REQUEST_APPROVAL","title":"Freigabe anfordern","dependsOn":["entwurf"]},
                          {"key":"senden","action":"SEND_LETTER","title":"Senden","dependsOn":["freigabe"]},
                          {"key":"warten","action":"WAIT_FOR_REPLY","title":"Auf die Antwort warten","dependsOn":["senden"]}]}
                """);
        assertThat(send(a, post("/api/v1/cases/" + caseId + "/plan/advance"), "{}").getResponse().getContentAsString()).contains("\"ranAutomatically\":1");
        assertThat(send(a, post("/api/v1/cases/" + caseId + "/plan/steps/senden/start"), "{}").getResponse().getStatus())
                .as("nothing is sent before the approval").isEqualTo(409);
        send(a, post("/api/v1/cases/" + caseId + "/plan/steps/entwurf/start"), "{}");
        send(a, post("/api/v1/cases/" + caseId + "/plan/steps/entwurf/complete"), "{}");

        // 5. The approval is bound to the letter; a change after the request invalidates it.
        UUID other = a.userId(); // the platform administrator of the workspace asks, a colleague would decide; here the same person asks via the service
        ApprovalService.Request request = RequestContext.runAs(a.tenantId(), () -> approvals.request(a.tenantId(), "OUTBOUND_MESSAGE", letter,
                "OUTBOUND_MESSAGE", "Widerspruch senden", "Betrag weicht vom Vertrag ab", null, "OUTGOING", null, other));
        jdbc.update("update outbound_messages set body_text = 'Der Betrag weicht vom Vertrag ab. Bitte um Korrektur.' where id = ?", letter);
        UUID approver = RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject("""
                insert into users (id, email, status, display_name, locale, time_zone, failed_login_attempts, must_change_password)
                values (gen_random_uuid(), ?, 'ACTIVE', 'Freigeber', 'de', 'Europe/Berlin', 0, false) returning id
                """, UUID.class, "approver-" + UUID.randomUUID().toString().substring(0, 6) + "@example.de"));
        RequestContext.runAs(a.tenantId(), () -> jdbc.update("""
                insert into memberships (id, tenant_id, user_id, status, is_owner) values (gen_random_uuid(), ?, ?, 'ACTIVE', false)
                """, a.tenantId(), approver));
        try {
            RequestContext.runAs(a.tenantId(), () -> approvals.decide(a.tenantId(), request.approvalId(), approver, true, "ok"));
            throw new AssertionError("the changed letter must not be approvable on the old request");
        }
        catch (ApiException expected) {
            assertThat(expected.getMessage()).contains("Inhalt");
        }

        // 6. The provider took the letter and delivered it; the other side answers.
        jdbc.update("update outbound_messages set status = 'SENT', provider_message_id = '<e2e-1@copilot.example>', sent_at = now(), approved_by = ?, approved_at = now() where id = ?",
                approver, letter);
        jdbc.update("insert into message_delivery_events (outbound_message_id, tenant_id, event_type, proves_delivery) values (?, ?, 'DELIVERED', true)", letter, a.tenantId());
        send(a, post("/api/v1/cases/" + caseId + "/plan/steps/freigabe/start"), "{}");
        send(a, post("/api/v1/cases/" + caseId + "/plan/steps/freigabe/complete"), "{}");
        send(a, post("/api/v1/cases/" + caseId + "/plan/steps/senden/start"), "{}");
        send(a, post("/api/v1/cases/" + caseId + "/plan/steps/senden/complete"), "{}");
        assertThat(send(a, post("/api/v1/cases/" + caseId + "/status"), "{\"status\":\"COMPLETED\"}").getResponse().getStatus())
                .as("neither sending nor delivery is an answer").isEqualTo(409);

        UUID mail = RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject("""
                insert into mail_messages (id, tenant_id, direction, subject, from_address, in_reply_to, received_at, processing_status)
                values (gen_random_uuid(), ?, 'INBOUND', 'AW: Widerspruch Rechnung S-2', 'service@stadtwerke.example', '<e2e-1@copilot.example>', now(), 'RECEIVED') returning id
                """, UUID.class, a.tenantId()));
        assertThat(RequestContext.runAs(a.tenantId(), () -> replies.link(a.tenantId(), mail)).caseId()).isEqualTo(caseId);

        // 7. Now the criteria are met by facts, and the case can be completed.
        assertThat(send(a, post("/api/v1/cases/" + caseId + "/status"), "{\"status\":\"COMPLETED\"}").getResponse().getStatus()).isEqualTo(200);
        assertThat(RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject(
                "select status from workflow_runs where case_id = ? and trigger_type = 'CASE_PLAN'", String.class, caseId))).isEqualTo("COMPLETED");
        assertThat(RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject(
                "select count(*) from audit_events where tenant_id = ? and action like 'RADAR_FINDING_%'", Integer.class, a.tenantId()))).isGreaterThanOrEqualTo(2);

        // Variant: another workspace sees none of it.
        Account stranger = newTenant();
        assertThat((Integer) JsonPath.read(mockMvc.perform(get("/api/v1/radar/findings").cookie(stranger.cookie())).andReturn().getResponse().getContentAsString(), "$.total")).isZero();
        mockMvc.perform(get("/api/v1/cases/" + caseId + "/plan").cookie(stranger.cookie())).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("variants: no contract still gives the finding without a contract claim; an unreadable amount (OCR) gives no finding and no guess")
    void variants() throws Exception {
        Account a = newTenant();
        UUID contact = RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject("""
                insert into contacts (id, tenant_id, contact_type, display_name, organization_name, is_supplier)
                values (gen_random_uuid(), ?, 'ORGANIZATION', 'Lieferant', 'Lieferant', true) returning id
                """, UUID.class, a.tenantId()));
        UUID d1 = upload(a, "Rechnung A");
        UUID d2 = upload(a, "Rechnung B");
        UUID d3 = upload(a, "Rechnung C");
        invoice(a, contact, d1, null, "A-1", "100.00", "2026-08-10");
        invoice(a, contact, d2, null, "A-2", "150.00", "2026-09-10");
        RequestContext.runAs(a.tenantId(), () -> radar.evaluateDocument(a.tenantId(), d2));
        String list = mockMvc.perform(get("/api/v1/radar/findings").cookie(a.cookie())).andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(list, "$.items[0].description")).doesNotContain("Vertrag");

        // The amount of the third invoice could not be read: nothing is compared, nothing is invented.
        RequestContext.runAs(a.tenantId(), () -> jdbc.update("""
                insert into invoices (id, tenant_id, direction, contact_id, document_id, invoice_number, issue_date)
                values (gen_random_uuid(), ?, 'INCOMING', ?, ?, 'A-3', '2026-10-10')
                """, a.tenantId(), contact, d3));
        assertThat(RequestContext.runAs(a.tenantId(), () -> radar.evaluateDocument(a.tenantId(), d3)).created()).isZero();
    }
}
