package de.buerokratiecopilot.templates.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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
 * Blocked generated documents over HTTP (spec §15; E-003).
 *
 * <p>What this checks beyond the routes: a blocked document is <strong>produced and not usable</strong>, and
 * the answer says so in words rather than letting a count be read as letters that went out; and the
 * <strong>missing fields are named</strong>, because "three are blocked" sends somebody looking while "the
 * reference and the amount were never entered" tells them what to do.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GeneratedDocumentControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    private record Session(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private Session register() throws Exception {
        String email = "gen-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Erzeugte Dokumente","accountType":"COMPANY"}
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

    /**
     * A generated document in the blocked state.
     *
     * <p>Written directly because the state is what these routes report on, and generating one through the
     * template path would need a published template and its fields — the fixture would then be testing the
     * generation rather than the reporting.</p>
     */
    private UUID blockedDocument(Session session, List<String> unresolved) {
        String status = unresolved.isEmpty() ? "GENERATED" : "BLOCKED_UNRESOLVED_FIELDS";
        UUID documentId = jdbc.queryForObject("""
                insert into documents (id, tenant_id, original_filename, internal_name, mime_type,
                                       size_bytes, content_hash, source, processing_status, review_status)
                values (gen_random_uuid(), ?, 'schreiben.html', 'schreiben.html', 'text/html', 10, ?,
                        'GENERATED', 'READY', 'NOT_REQUIRED')
                returning id
                """, UUID.class, session.tenantId(),
                "%064x".formatted(java.util.concurrent.ThreadLocalRandom.current().nextLong()));
        String fields = unresolved.stream().map(f -> "\"" + f + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        return RequestContext.runAs(session.tenantId(), () -> jdbc.queryForObject("""
                insert into generated_documents (id, tenant_id, rendered_document_id, subject_type, title,
                                                 output_format, body_html, status, unresolved_fields,
                                                 field_values, generated_by)
                values (gen_random_uuid(), ?, ?, 'CASE', 'Mahnung', 'HTML', '<p>Text</p>',
                        ?, ?::jsonb, '{}'::jsonb, ?)
                returning id
                """, UUID.class, session.tenantId(), documentId, status, fields, session.userId()));
    }

    // ---------------------------------------------------------------- the count

    @Test
    @DisplayName("an account with nothing blocked is told that nothing is waiting")
    void nothingBlockedIsSaidPlainly() throws Exception {
        Session session = register();

        mockMvc.perform(get("/api/v1/generated-documents/blocked").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocked").value(0))
                .andExpect(jsonPath("$.note").isNotEmpty());
    }

    @Test
    @DisplayName("a blocked document is counted and the note says it is not usable")
    void aBlockedDocumentIsCounted() throws Exception {
        Session session = register();
        blockedDocument(session, java.util.List.of("akte_zeichen", "betrag"));

        // A blocked document is produced and not usable; a person reading a count has to know it is not a
        // count of letters that went out.
        MvcResult result = mockMvc.perform(get("/api/v1/generated-documents/blocked")
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocked").value(1))
                .andReturn();

        org.assertj.core.api.Assertions.assertThat(
                        JsonPath.read(result.getResponse().getContentAsString(), "$.note").toString())
                .contains("nicht verwendbar");
    }

    @Test
    @DisplayName("the missing fields are named, not counted")
    void theMissingFieldsAreNamed() throws Exception {
        Session session = register();
        UUID documentId = blockedDocument(session, java.util.List.of("akte_zeichen", "betrag"));

        // 'Three are blocked' sends somebody looking; naming the fields tells them what to do.
        MvcResult result = mockMvc.perform(
                        get("/api/v1/generated-documents/{id}/unresolved-fields", documentId)
                                .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complete").value(false))
                .andExpect(jsonPath("$.unresolvedFields[0]").isNotEmpty())
                .andExpect(jsonPath("$.note").isNotEmpty())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body).contains("akte_zeichen").contains("betrag");
    }

    @Test
    @DisplayName("a document that is not blocked has nothing missing")
    void aCompleteDocumentSaysSo() throws Exception {
        Session session = register();
        // The schema forbids a document in the blocked state without naming what blocks it
        // (generated_documents_blocked_chk), so a document with nothing missing is in another state.
        UUID documentId = blockedDocument(session, java.util.List.of());

        mockMvc.perform(get("/api/v1/generated-documents/{id}/unresolved-fields", documentId)
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complete").value(true))
                .andExpect(jsonPath("$.unresolvedFields").isEmpty())
                .andExpect(jsonPath("$.note").isNotEmpty());
    }

    @Test
    @DisplayName("an unknown document is reported rather than answered with an empty list")
    void anUnknownDocumentIsReported() throws Exception {
        Session session = register();

        // Stricter than the extraction routes, and better here: this asks about one named document, so 'there
        // is no such document' is the answer - an empty list would say the platform had looked and found
        // nothing missing, which is a different statement.
        mockMvc.perform(get("/api/v1/generated-documents/{id}/unresolved-fields", UUID.randomUUID())
                        .cookie(session.cookie()))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- what is refused

    @Test
    @DisplayName("without a session nothing is read")
    void withoutASessionNothingIsRead() throws Exception {
        mockMvc.perform(get("/api/v1/generated-documents/blocked"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/generated-documents/{id}/unresolved-fields", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("another account's document is not counted for this one")
    void accountsAreSeparated() throws Exception {
        Session first = register();
        Session second = register();
        blockedDocument(first, java.util.List.of("akte_zeichen"));

        mockMvc.perform(get("/api/v1/generated-documents/blocked").cookie(second.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocked").value(0));
    }

    @Test
    @DisplayName("the template permissions exist and are held, which is what makes these reachable")
    void theTemplatePermissionsAreHeld() {
        Integer held = jdbc.queryForObject("""
                select count(*) from role_permissions rp
                join roles r on r.id = rp.role_id
                join permissions p on p.id = rp.permission_id
                where p.code in ('templates.view','templates.create')
                  and r.tenant_id is null and r.code in ('OWNER','ADMIN')
                """, Integer.class);

        org.assertj.core.api.Assertions.assertThat(held)
                .as("the template permissions must be granted to OWNER and ADMIN, or these are dead")
                .isGreaterThan(0);
    }
}
