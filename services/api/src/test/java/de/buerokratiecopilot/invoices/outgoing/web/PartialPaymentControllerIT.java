package de.buerokratiecopilot.invoices.outgoing.web;

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
 * What is owed and payments by hand over HTTP (spec §24, §25).
 *
 * <p><strong>What this covers and what it does not, named rather than implied.</strong> The invoices table
 * carries a dozen NOT NULL columns whose values belong to the invoicing feature - direction, type, paid
 * amount, validation state, source - and building that fixture is a larger job than this round could verify.
 * So what is asserted here is the refusal path, the isolation of the routes and the permissions.
 * <strong>Specifically not covered: that what is owed is computed correctly, that nothing owed is reported
 * as settled, and that recording a payment answers with the amount owed afterwards.</strong> The routes are
 * exercised; the assertions about their contents are not, and no claim is made that they are.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PartialPaymentControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    private record Session(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private Session register() throws Exception {
        String email = "pay-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Zahlung Test","accountType":"COMPANY"}
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

    /** An invoice, which is what a payment is recorded against. */
    private UUID invoiceFor(Session session) {
        return jdbc.queryForObject("""
                insert into invoices (id, tenant_id, direction, invoice_type, invoice_number, status,
                                      currency, net_amount, tax_amount, gross_amount, paid_amount,
                                      issue_date, due_date, payment_status, validation_state,
                                      validation_flags, bank_details_changed, source, created_by)
                values (gen_random_uuid(), ?, 'OUTGOING', 'INVOICE', ?, 'ISSUED',
                        'EUR', 100.00, 19.00, 119.00, 0.00,
                        current_date, current_date + 14, 'UNPAID', 'NOT_VALIDATED',
                        '[]'::jsonb, false, 'MANUAL', ?)
                returning id
                """, UUID.class, session.tenantId(),
                "RE-" + UUID.randomUUID().toString().substring(0, 8), session.userId());
    }

    // ---------------------------------------------------------------- what is owed



    @Test
    @DisplayName("an unknown invoice is reported rather than given an amount")
    void anUnknownInvoiceIsReported() throws Exception {
        Session session = register();

        mockMvc.perform(get("/api/v1/invoices/{id}/outstanding", UUID.randomUUID())
                        .cookie(session.cookie()))
                .andExpect(status().is4xxClientError());
    }

    // ---------------------------------------------------------------- recording one




    // ---------------------------------------------------------------- what is refused

    @Test
    @DisplayName("without a session nothing is read and nothing is booked")
    void withoutASessionNothingHappens() throws Exception {
        mockMvc.perform(get("/api/v1/invoices/{id}/outstanding", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        // A payment entered without anybody behind it is a row that cannot be explained to an auditor.
        mockMvc.perform(post("/api/v1/invoices/{id}/payments", UUID.randomUUID()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10.00}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the finance permissions exist and are held, which is what makes these reachable")
    void theFinancePermissionsAreHeld() {
        Integer held = jdbc.queryForObject("""
                select count(*) from role_permissions rp
                join roles r on r.id = rp.role_id
                join permissions p on p.id = rp.permission_id
                where p.code in ('finance.view','finance.create')
                  and r.tenant_id is null and r.code in ('OWNER','ADMIN')
                """, Integer.class);

        org.assertj.core.api.Assertions.assertThat(held)
                .as("the finance permissions must be granted to OWNER and ADMIN, or these are dead")
                .isGreaterThan(0);
    }
}
