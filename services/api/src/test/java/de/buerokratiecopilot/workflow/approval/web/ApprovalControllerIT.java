package de.buerokratiecopilot.workflow.approval.web;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * Approvals and payments over HTTP (spec §14, §20; §28.3 steps 7 to 9).
 *
 * <p>Both services were implemented and tested with no endpoint, so the chain the specification requires
 * could not be walked by any client. What this checks is that the routes exist, and — more importantly —
 * that the rule the whole feature rests on survives the journey through HTTP: <strong>nobody approves their
 * own request</strong>, and the decider is taken from the session rather than from the body.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApprovalControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    private record Session(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private Session register() throws Exception {
        String email = "appr-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Freigabe Test","accountType":"COMPANY"}
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

    /** An approval request, raised over HTTP. */
    private UUID ask(Session session) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/approvals").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subjectType":"invoice","subjectId":"%s","approvalType":"INVOICE_APPROVAL",
                                 "title":"Rechnung prüfen","justification":"Betrag geprüft",
                                 "amount":119.00,"direction":"OUTGOING"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(JsonPath.read(created.getResponse().getContentAsString(), "$.approvalId"));
    }

    // ---------------------------------------------------------------- the chain

    @Test
    @DisplayName("an approval is raised and appears among the open ones")
    void anApprovalIsRaised() throws Exception {
        Session session = register();
        UUID approvalId = ask(session);

        mockMvc.perform(get("/api/v1/approvals").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + approvalId + "')]").exists())
                .andExpect(jsonPath("$[0].status").value("PENDING"));
    }

    @Test
    @DisplayName("the request says how many confirmations it needs, from the configured rules")
    void theRequestSaysHowManyConfirmations() throws Exception {
        Session session = register();

        MvcResult created = mockMvc.perform(post("/api/v1/approvals").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subjectType":"invoice","subjectId":"%s","approvalType":"INVOICE_APPROVAL",
                                 "title":"Große Rechnung","amount":5000.00,"direction":"OUTGOING"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.levelsRequired").isNumber())
                .andReturn();

        // The level comes from the customer's rules and is copied onto the request, so a later change to
        // the matrix does not move the bar for a decision somebody is already considering.
        int levels = JsonPath.read(created.getResponse().getContentAsString(), "$.levelsRequired");
        org.assertj.core.api.Assertions.assertThat(levels).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("the requester is told they may not decide their own request, before they try")
    void theRequesterMayNotDecide() throws Exception {
        Session session = register();
        UUID approvalId = ask(session);

        mockMvc.perform(get("/api/v1/approvals/{id}/may-decide", approvalId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mayDecide").value(false));
    }

    @Test
    @DisplayName("deciding one's own request is refused over HTTP as well")
    void decidingOnesOwnRequestIsRefused() throws Exception {
        Session session = register();
        UUID approvalId = ask(session);

        // The decider is taken from the session, so a body cannot name somebody else - and the requester
        // naming themselves is exactly what this refuses.
        mockMvc.perform(post("/api/v1/approvals/{id}/decision", approvalId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true,\"note\":\"Passt schon\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the history of a request is served")
    void theHistoryIsServed() throws Exception {
        Session session = register();
        UUID approvalId = ask(session);

        mockMvc.perform(get("/api/v1/approvals/{id}/history", approvalId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].event_type").value("REQUESTED"));
    }

    @Test
    @DisplayName("a request can be withdrawn")
    void aRequestCanBeWithdrawn() throws Exception {
        Session session = register();
        UUID approvalId = ask(session);

        // The answer used to be 409 for a request that was PENDING: the service wrote decided_at and
        // decided_by on a withdrawal, and the schema refuses that - those columns name a person who
        // decided something, and a withdrawal is not a decision.
        mockMvc.perform(post("/api/v1/approvals/{id}/cancel", approvalId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Zurückgezogen\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("overdue requests are escalated, and the answer counts them")
    void overdueRequestsAreEscalated() throws Exception {
        Session session = register();

        mockMvc.perform(post("/api/v1/approvals/escalate-overdue").with(csrf())
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.escalated").isNumber());
    }

    // ---------------------------------------------------------------- payments

    @Test
    @DisplayName("a payment without an amount is refused before anything is prepared")
    void aPaymentWithoutAnAmountIsRefused() throws Exception {
        Session session = register();

        mockMvc.perform(post("/api/v1/payments").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"EUR\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a payment from an unapproved invoice is refused, because that is what the approval gates")
    void aPaymentFromAnUnapprovedInvoiceIsRefused() throws Exception {
        Session session = register();

        mockMvc.perform(post("/api/v1/payments").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"invoiceId":"%s","amount":119.00,"currency":"EUR",
                                 "counterpartyName":"Lieferant","paymentDate":"2026-04-01"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().is4xxClientError());
    }

    // ---------------------------------------------------------------- what is refused

    @Test
    @DisplayName("without a session nothing is read or raised")
    void withoutASessionNothingHappens() throws Exception {
        mockMvc.perform(get("/api/v1/approvals")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/approvals").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectType\":\"invoice\",\"subjectId\":\"%s\"}"
                                .formatted(UUID.randomUUID())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/payments/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an approval without a subject is refused")
    void anApprovalWithoutASubjectIsRefused() throws Exception {
        Session session = register();

        mockMvc.perform(post("/api/v1/approvals").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectType\":\"invoice\",\"title\":\"Ohne Gegenstand\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the history of an unknown request is reported")
    void theHistoryOfAnUnknownRequestIsReported() throws Exception {
        Session session = register();

        mockMvc.perform(get("/api/v1/approvals/{id}/history", UUID.randomUUID())
                        .cookie(session.cookie()))
                .andExpect(status().isNotFound());
    }
}
