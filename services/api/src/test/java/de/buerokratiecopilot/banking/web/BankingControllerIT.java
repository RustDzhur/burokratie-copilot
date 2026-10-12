package de.buerokratiecopilot.banking.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * Banking over HTTP (spec §25).
 *
 * <p><strong>What this does not do, and it is deliberate.</strong> No CAMT file is imported here, because a
 * bank statement fixture means account numbers, an IBAN and amounts that look like somebody's real account —
 * and an invented one is exactly what a later reader mistakes for data that came from somewhere. What is
 * asserted is the account registration, the refusal of an empty or unparseable file, the isolation and the
 * permissions; the parsing itself is covered by the service tests that already read real fixtures.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BankingControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    private record Session(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private Session register() throws Exception {
        String email = "bank-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Bank Test","accountType":"COMPANY"}
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

    /** An account, with a credential reference that must never come back. */
    private record Account(UUID accountId, String credentialRef) {
    }

    private Account accountFor(Session session) throws Exception {
        String reference = "ref-" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult created = mockMvc.perform(post("/api/v1/banking/accounts").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Geschäftskonto","bankName":"Musterbank",
                                 "accountHolder":"Muster GmbH","currency":"EUR",
                                 "credentialRef":"%s"}
                                """.formatted(reference)))
                .andExpect(status().isCreated())
                .andReturn();
        return new Account(UUID.fromString(JsonPath.read(
                created.getResponse().getContentAsString(), "$.accountId")), reference);
    }

    // ---------------------------------------------------------------- the account

    @Test
    @DisplayName("a bank account is registered and the credential reference never comes back")
    void theCredentialReferenceNeverComesBack() throws Exception {
        Session session = register();
        String reference = "ref-" + UUID.randomUUID().toString().substring(0, 8);

        MvcResult created = mockMvc.perform(post("/api/v1/banking/accounts").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Geschäftskonto","bankName":"Musterbank",
                                 "accountHolder":"Muster GmbH","currency":"EUR",
                                 "credentialRef":"%s"}
                                """.formatted(reference)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountId").isNotEmpty())
                .andExpect(jsonPath("$.name").value("Geschäftskonto"))
                .andExpect(jsonPath("$.note").isNotEmpty())
                .andReturn();

        // It points at a stored secret; echoing it would put the reference into a browser session and into
        // every screenshot of the page.
        org.assertj.core.api.Assertions.assertThat(created.getResponse().getContentAsString())
                .doesNotContain(reference);
    }

    @Test
    @DisplayName("a bank account without a name is refused")
    void aBankAccountWithoutANameIsRefused() throws Exception {
        Session session = register();

        mockMvc.perform(post("/api/v1/banking/accounts").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankName\":\"Musterbank\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- importing

    @Test
    @DisplayName("an empty statement file is refused, because there is nothing to read")
    void anEmptyStatementIsRefused() throws Exception {
        Session session = register();
        Account account = accountFor(session);

        mockMvc.perform(multipart("/api/v1/banking/accounts/{id}/statements", account.accountId())
                        .file(new MockMultipartFile("file", "leer.xml", "application/xml", new byte[0]))
                        .with(csrf())
                        .cookie(session.cookie()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a file that is not a statement is refused rather than read as an empty one")
    void aFileThatIsNotAStatementIsRefused() throws Exception {
        Session session = register();
        Account account = accountFor(session);

        // An import of nothing that answered success would look like a statement with no transactions in it,
        // which is a different statement from a file that could not be read.
        mockMvc.perform(multipart("/api/v1/banking/accounts/{id}/statements", account.accountId())
                        .file(new MockMultipartFile("file", "x.xml", "application/xml",
                                "<html>kein Auszug</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .with(csrf())
                        .cookie(session.cookie()))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- matching

    @Test
    @DisplayName("confirming a match needs a transaction, an invoice and an amount")
    void confirmingNeedsItsFields() throws Exception {
        Session session = register();

        mockMvc.perform(post("/api/v1/banking/matches").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionId\":\"%s\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/banking/matches").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionId\":\"%s\",\"invoiceId\":\"%s\"}"
                                .formatted(UUID.randomUUID(), UUID.randomUUID())))
                // No amount is allowed (the whole transaction is matched), so what is wrong here is that the
                // transaction does not exist.
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("confirming an unknown transaction is reported rather than booked")
    void confirmingAnUnknownTransactionIsReported() throws Exception {
        Session session = register();

        mockMvc.perform(post("/api/v1/banking/matches").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transactionId":"%s","invoiceId":"%s","amount":119.00,"matchType":"MANUAL"}
                                """.formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("a decision on a proposal needs both sides")
    void aDecisionNeedsBothSides() throws Exception {
        Session session = register();

        mockMvc.perform(post("/api/v1/banking/matches/proposal").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionId\":\"%s\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- what is refused

    @Test
    @DisplayName("without a session nothing is registered, imported or booked")
    void withoutASessionNothingHappens() throws Exception {
        mockMvc.perform(post("/api/v1/banking/accounts").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Konto\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(multipart("/api/v1/banking/accounts/{id}/statements", UUID.randomUUID())
                        .file(new MockMultipartFile("file", "x.xml", "application/xml", new byte[] {1}))
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/banking/matches").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("another account's bank account is not imported into")
    void anotherAccountsBankAccountIsNotReachable() throws Exception {
        Session first = register();
        Session second = register();
        Account account = accountFor(first);

        mockMvc.perform(multipart("/api/v1/banking/accounts/{id}/statements", account.accountId())
                        .file(new MockMultipartFile("file", "x.xml", "application/xml", new byte[] {1, 2}))
                        .with(csrf())
                        .cookie(second.cookie()))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("the finance permissions exist and are held, which is what makes these reachable")
    void theFinancePermissionsAreHeld() {
        Integer held = jdbc.queryForObject("""
                select count(*) from role_permissions rp
                join roles r on r.id = rp.role_id
                join permissions p on p.id = rp.permission_id
                where p.code in ('finance.create','finance.reconcile')
                  and r.tenant_id is null and r.code in ('OWNER','ADMIN')
                """, Integer.class);

        org.assertj.core.api.Assertions.assertThat(held)
                .as("the finance permissions must be granted to OWNER and ADMIN, or these are dead")
                .isGreaterThan(0);
    }
}
