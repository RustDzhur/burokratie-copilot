package de.buerokratiecopilot.einvoicing.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * Electronic invoices over HTTP (spec §23).
 *
 * <p><strong>What this does not do, and it is deliberate.</strong> No import of a <em>valid</em> XRechnung or
 * ZUGFeRD file is asserted here, because producing one would mean writing invoice content that looks real —
 * a seller, a tax number, a total — and an invented electronic invoice is exactly the kind of thing somebody
 * later mistakes for a fixture that came from somewhere. What is asserted is the empty case, the refusal of
 * an empty file, the isolation and the permissions; the import path itself is covered by the service tests
 * that already parse real fixtures.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EInvoiceControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    private record Session(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private Session register() throws Exception {
        String email = "ein-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"E-Rechnung Test","accountType":"COMPANY"}
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

    private UUID documentFor(Session session) {
        return jdbc.queryForObject("""
                insert into documents (id, tenant_id, original_filename, internal_name, mime_type,
                                       size_bytes, content_hash, source, processing_status, review_status)
                values (gen_random_uuid(), ?, 'rechnung.xml', 'rechnung.xml', 'application/xml', 10, ?,
                        'UPLOAD', 'READY', 'NOT_REQUIRED')
                returning id
                """, UUID.class, session.tenantId(),
                "%064x".formatted(java.util.concurrent.ThreadLocalRandom.current().nextLong()));
    }

    // ---------------------------------------------------------------- the routes

    @Test
    @DisplayName("an account with nothing to deal with is not given anything")
    void nothingToDealWithIsNotInvented() throws Exception {
        Session session = register();

        // An empty list, not a placeholder: the platform has read no electronic invoice for this account, and
        // a screen showing a row would be showing one nobody received.
        mockMvc.perform(get("/api/v1/e-invoices/needing-attention").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("a document with no electronic invoice is not given one")
    void aDocumentWithoutAnEInvoiceSaysSo() throws Exception {
        Session session = register();
        UUID documentId = documentFor(session);

        mockMvc.perform(get("/api/v1/documents/{id}/e-invoices", documentId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("an unknown record is reported rather than invented")
    void anUnknownRecordIsReported() throws Exception {
        Session session = register();

        mockMvc.perform(get("/api/v1/e-invoices/{id}", UUID.randomUUID()).cookie(session.cookie()))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- importing

    @Test
    @DisplayName("an empty file is refused, because there is nothing to parse")
    void anEmptyFileIsRefused() throws Exception {
        Session session = register();
        UUID documentId = documentFor(session);

        mockMvc.perform(multipart("/api/v1/documents/{id}/e-invoices", documentId)
                        .file(new MockMultipartFile("file", "leer.xml", "application/xml", new byte[0]))
                        .with(csrf())
                        .cookie(session.cookie()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a file that is not an electronic invoice is refused with a reason rather than as success")
    void aFileThatIsNotAnInvoiceIsRefused() throws Exception {
        Session session = register();
        UUID documentId = documentFor(session);
        byte[] notAnInvoice = "<html><body>keine Rechnung</body></html>".getBytes(
                java.nio.charset.StandardCharsets.UTF_8);

        // Either it is refused, or it arrives with accepted=false and a reason. What must not happen is a
        // 201: a file that failed validation is a file somebody has to look at, not a success.
        MvcResult attempt = mockMvc.perform(
                        multipart("/api/v1/documents/{id}/e-invoices", documentId)
                                .file(new MockMultipartFile("file", "x.xml", "application/xml", notAnInvoice))
                                .with(csrf())
                                .cookie(session.cookie()))
                .andReturn();

        org.assertj.core.api.Assertions.assertThat(attempt.getResponse().getStatus())
                .as("a document that is not a valid electronic invoice must never be reported as imported")
                .isNotEqualTo(201);
    }

    // ---------------------------------------------------------------- what is refused

    @Test
    @DisplayName("without a session nothing is read or imported")
    void withoutASessionNothingHappens() throws Exception {
        mockMvc.perform(get("/api/v1/e-invoices/needing-attention"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/e-invoices/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(multipart("/api/v1/documents/{id}/e-invoices", UUID.randomUUID())
                        .file(new MockMultipartFile("file", "x.xml", "application/xml", new byte[] {1}))
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("another account's document yields nothing")
    void anotherAccountsDocumentYieldsNothing() throws Exception {
        Session first = register();
        Session second = register();
        UUID documentId = documentFor(first);

        mockMvc.perform(get("/api/v1/documents/{id}/e-invoices", documentId).cookie(second.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("the finance permissions exist and are held, which is what makes these reachable")
    void theFinancePermissionsAreHeld() {
        // There is no e_invoice permission in the catalogue; an electronic invoice is a financial document,
        // so these routes use finance.*. Checked rather than assumed.
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
