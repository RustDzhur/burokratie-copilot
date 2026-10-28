package de.buerokratiecopilot.invoices.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * An invoice can leave the product as a document (spec §14.2).
 *
 * <p><strong>What was wrong.</strong> {@code InvoicePdfRenderer} was written, tested and called by nothing.
 * An invoice could be created, validated and approved, and there was no way to get it out of the system as a
 * PDF — which is the one thing a customer does with an invoice. The renderer had a test, so the code looked
 * covered; nobody checked that a route reached it.</p>
 *
 * <p>The test walks the way a person would: record an invoice, then ask for its PDF, and check that what comes
 * back is a PDF rather than an error or an empty body.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InvoicePdfIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    private record Session(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private Session register() throws Exception {
        String email = "pdf-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"PDF Test","accountType":"COMPANY"}
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

    private String record(Session session, String number) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/invoices").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"invoiceNumber":"%s","supplierName":"Muster Lieferant GmbH",
                                 "direction":"INCOMING","invoiceType":"INVOICE","currency":"EUR",
                                 "netAmount":"100.00","taxAmount":"19.00","grossAmount":"119.00",
                                 "iban":"DE89370400440532013000","issueDate":"2026-03-01",
                                 "dueDate":"2026-03-31","paymentTermsDays":30,
                                 "lines":[{"lineNumber":1,"description":"Leistung","quantity":1,
                                           "unit":"Stk","unitPrice":"100.00","netAmount":"100.00",
                                           "taxRatePercent":"19.00","taxAmount":"19.00",
                                           "grossAmount":"119.00"}]}
                                """.formatted(number)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    }

    @Test
    @DisplayName("an approved invoice comes back as a PDF")
    void anInvoiceComesBackAsPdf() throws Exception {
        Session session = register();
        String id = record(session, "RE-PDF-1");

        MvcResult result = mockMvc.perform(get("/api/v1/invoices/{id}/pdf", id)
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentType())
                .as("the browser must treat it as a document, not as text")
                .contains("application/pdf");
        byte[] body = result.getResponse().getContentAsByteArray();
        assertThat(body.length).as("a PDF that is empty is not a PDF").isGreaterThan(500);
        // The file signature. Without this the route could return an error page with a PDF content type.
        assertThat(new String(body, 0, 4, java.nio.charset.StandardCharsets.US_ASCII))
                .isEqualTo("%PDF");
    }

    @Test
    @DisplayName("the file is named after the invoice number")
    void theFileIsNamedAfterTheNumber() throws Exception {
        Session session = register();
        String id = record(session, "RE-PDF-2");

        MvcResult result = mockMvc.perform(get("/api/v1/invoices/{id}/pdf", id)
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getHeader("Content-Disposition"))
                .as("a downloaded file called 'document' helps nobody")
                .contains("RE-PDF-2");
    }

    @Test
    @DisplayName("the invoice comes back as an XRechnung that the platform reads back")
    void theInvoiceComesBackAsXRechnung() throws Exception {
        Session session = register();
        String id = record(session, "RE-XR-1");

        MvcResult result = mockMvc.perform(get("/api/v1/invoices/{id}/xrechnung", id)
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentType()).contains("xml");
        byte[] body = result.getResponse().getContentAsByteArray();

        // The strongest check available without a validator: the platform's own parser reads it back, and it
        // must not call the result incomplete.
        var parsed = de.buerokratiecopilot.einvoicing.EInvoiceParser.parse(body);
        assertThat(parsed.invoiceNumber()).isEqualTo("RE-XR-1");
        assertThat(parsed.missingRequiredFields())
                .as("a generated e-invoice its own reader calls incomplete would be worse than none")
                .isEmpty();
        assertThat(parsed.lines()).hasSize(1);
        assertThat(result.getResponse().getHeader("Content-Disposition")).contains("RE-XR-1");
    }

    @Test
    @DisplayName("another account cannot fetch the invoice as a PDF")
    void anotherAccountCannotFetchIt() throws Exception {
        Session first = register();
        Session second = register();
        String id = record(first, "RE-PDF-3");

        mockMvc.perform(get("/api/v1/invoices/{id}/pdf", id).cookie(second.cookie()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("without a session the PDF is refused")
    void withoutASessionItIsRefused() throws Exception {
        mockMvc.perform(get("/api/v1/invoices/{id}/pdf", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
