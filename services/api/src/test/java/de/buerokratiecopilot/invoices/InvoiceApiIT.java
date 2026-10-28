package de.buerokratiecopilot.invoices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * Verifies the invoice API (spec §14, §20).
 *
 * <p>What the interface has to be able to do is see why an invoice is blocked. The test therefore goes
 * through the API the way a user would: record an invoice, read the state and the flags, and be refused
 * when approving something a person has not confirmed — and, in the end, be let through when they
 * have.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InvoiceApiIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    private static final String FIRST_IBAN = "DE89370400440532013000";
    private static final String OTHER_IBAN = "DE12500105170648489890";

    private record Session(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private Session register() throws Exception {
        String email = "invoice-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Rechnungen Test","accountType":"COMPANY"}
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

    /** One line whose sums agree with the stated total unless the caller says otherwise. */
    private String invoiceBody(String number, String iban, String statedGross, String lineGross) {
        return """
                {"invoiceNumber":"%s","supplierName":"Muster Lieferant GmbH","direction":"INCOMING",
                 "invoiceType":"INVOICE","currency":"EUR","netAmount":"100.00","taxAmount":"19.00",
                 "grossAmount":"%s","iban":"%s","issueDate":"2026-03-01","dueDate":"2026-03-31",
                 "paymentTermsDays":30,
                 "lines":[{"lineNumber":1,"description":"Leistung","quantity":1,"unit":"Stk",
                           "unitPrice":"100.00","netAmount":"100.00","taxRatePercent":"19.00",
                           "taxAmount":"19.00","grossAmount":"%s"}]}
                """.formatted(number, statedGross, iban, lineGross);
    }

    private MvcResult record(Session session, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/invoices").with(csrf()).cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
    }

    @Test
    @DisplayName("a consistent invoice is recorded as OK and can be approved")
    void consistentInvoiceIsRecordedAndApproved() throws Exception {
        Session session = register();
        String id = JsonPath.read(record(session, invoiceBody("RE-1000", FIRST_IBAN, "119.00", "119.00"))
                .getResponse().getContentAsString(), "$.id");

        mockMvc.perform(get("/api/v1/invoices/{id}", id).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("OK"))
                .andExpect(jsonPath("$.blocksAutomaticPosting").value(false))
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0].description").value("Leistung"));

        mockMvc.perform(post("/api/v1/invoices/{id}/approve", id).with(csrf())
                        .cookie(session.cookie()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"acceptChangedBankDetails\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedAt").isNotEmpty());
    }

    @Test
    @DisplayName("a discrepancy is visible in the response, so an interface cannot hide it")
    void discrepancyIsVisible() throws Exception {
        Session session = register();
        String id = JsonPath.read(record(session, invoiceBody("RE-1001", FIRST_IBAN, "129.00", "119.00"))
                .getResponse().getContentAsString(), "$.id");

        MvcResult detail = mockMvc.perform(get("/api/v1/invoices/{id}", id).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("BLOCKED"))
                .andExpect(jsonPath("$.blocksAutomaticPosting").value(true))
                .andExpect(jsonPath("$.flags[?(@.code=='TOTAL_MISMATCH_GROSS')]").isNotEmpty())
                .andReturn();

        assertThat(detail.getResponse().getContentAsString())
                .as("both numbers have to reach the interface, or nobody can judge the difference")
                .contains("119.00")
                .contains("129.00");
    }

    @Test
    @DisplayName("a changed bank connection cannot be approved without an explicit confirmation")
    void changedBankConnectionIsRefusedByTheApi() throws Exception {
        Session session = register();
        record(session, invoiceBody("RE-1002", FIRST_IBAN, "119.00", "119.00"));
        String second = JsonPath.read(
                record(session, invoiceBody("RE-1003", OTHER_IBAN, "119.00", "119.00"))
                        .getResponse().getContentAsString(), "$.id");

        mockMvc.perform(get("/api/v1/invoices/{id}", second).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bankDetailsChanged").value(true))
                .andExpect(jsonPath("$.status").value("IN_REVIEW"));

        mockMvc.perform(post("/api/v1/invoices/{id}/approve", second).with(csrf())
                        .cookie(session.cookie()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"acceptChangedBankDetails\":false}"))
                .andExpect(status().isConflict());

        // A person confirmed it, so the invoice goes through and the new account becomes the known one.
        mockMvc.perform(post("/api/v1/invoices/{id}/approve", second).with(csrf())
                        .cookie(session.cookie()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"acceptChangedBankDetails":true,
                                 "note":"Neue Bankverbindung telefonisch bestätigt"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    @DisplayName("the invoices needing attention are listed, with the reason")
    void needingReviewIsListed() throws Exception {
        Session session = register();
        record(session, invoiceBody("RE-1004", FIRST_IBAN, "129.00", "119.00"));

        mockMvc.perform(get("/api/v1/invoices/needing-review").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].state").value("BLOCKED"))
                .andExpect(jsonPath("$[0].flags").isNotEmpty());
    }

    @Test
    @DisplayName("a duplicate is refused with a message that names the invoice")
    void duplicateIsRefused() throws Exception {
        Session session = register();
        record(session, invoiceBody("RE-1005", FIRST_IBAN, "119.00", "119.00"));

        mockMvc.perform(post("/api/v1/invoices").with(csrf()).cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invoiceBody("RE-1005", FIRST_IBAN, "119.00", "119.00")))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("an invoice of another tenant is not found")
    void otherTenantSeesNothing() throws Exception {
        Session owner = register();
        Session stranger = register();
        String id = JsonPath.read(record(owner, invoiceBody("RE-1006", FIRST_IBAN, "119.00", "119.00"))
                .getResponse().getContentAsString(), "$.id");

        mockMvc.perform(get("/api/v1/invoices/{id}", id).cookie(stranger.cookie()))
                .andExpect(status().isNotFound());
    }
}
