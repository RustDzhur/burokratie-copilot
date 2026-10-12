package de.buerokratiecopilot.documents.extraction.web;

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
 * Text extraction over HTTP (spec §13; B-006).
 *
 * <p>What this checks beyond the routes: every page says <strong>whether its text was read from the text
 * layer or recognised by OCR</strong>, because those are not the same evidence; and <strong>a page with no
 * text is served as an empty page rather than dropped</strong>, because "read and found nothing" and "not
 * read" are different statements.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TextExtractionControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    private record Session(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private Session register() throws Exception {
        String email = "txt-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Text Test","accountType":"COMPANY"}
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

    /** A document with one stored version, because a page hangs off the version. */
    private UUID versionedDocument(Session session) {
        UUID documentId = jdbc.queryForObject("""
                insert into documents (id, tenant_id, original_filename, internal_name, mime_type,
                                       size_bytes, content_hash, source, processing_status, review_status)
                values (gen_random_uuid(), ?, 'akte.pdf', 'akte.pdf', 'application/pdf', 10, ?,
                        'UPLOAD', 'READY', 'NOT_REQUIRED')
                returning id
                """, UUID.class, session.tenantId(),
                "%064x".formatted(java.util.concurrent.ThreadLocalRandom.current().nextLong()));
        UUID blobId = jdbc.queryForObject("""
                insert into document_blobs (id, tenant_id, storage_key, size_bytes, content_hash,
                                            content_type, scan_status, quarantined)
                values (gen_random_uuid(), ?, ?, 10, ?, 'application/pdf', 'CLEAN', false)
                returning id
                """, UUID.class, session.tenantId(),
                "tenants/" + session.tenantId() + "/" + UUID.randomUUID() + ".pdf",
                "%064x".formatted(java.util.concurrent.ThreadLocalRandom.current().nextLong()));
        jdbc.update("""
                insert into document_versions (id, document_id, tenant_id, version, blob_id, content_hash,
                                               size_bytes, mime_type, original_filename, created_by)
                values (gen_random_uuid(), ?, ?, 1, ?, ?, 10, 'application/pdf', 'akte.pdf', ?)
                """, documentId, session.tenantId(), blobId,
                "%064x".formatted(java.util.concurrent.ThreadLocalRandom.current().nextLong()),
                session.userId());
        return documentId;
    }

    private void page(Session session, UUID documentId, int number, String text, boolean ocr) {
        // document_pages is protected by a policy that reaches the tenant through the document.
        RequestContext.runAs(session.tenantId(), () -> jdbc.update("""
                insert into document_pages (id, document_id, version_id, page_number, text_content,
                                            ocr_used, ocr_engine, ocr_language, text_blocks)
                select gen_random_uuid(), ?, v.id, ?, ?, ?, ?, ?, '[]'::jsonb
                  from document_versions v where v.document_id = ? limit 1
                """, documentId, number, text, ocr, ocr ? "TESSERACT" : null, ocr ? "deu" : null,
                documentId));
    }

    // ---------------------------------------------------------------- the catalogue

    @Test
    @DisplayName("the formats the platform can read are served")
    void theSupportedFormatsAreServed() throws Exception {
        Session session = register();

        mockMvc.perform(get("/api/v1/documents/extraction/supported-formats").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.formats").isArray())
                .andExpect(jsonPath("$.note").isNotEmpty());
    }

    // ---------------------------------------------------------------- the pages

    @Test
    @DisplayName("a document nobody has read has no pages")
    void aDocumentNobodyHasReadHasNoPages() throws Exception {
        Session session = register();
        UUID documentId = versionedDocument(session);

        // No columns for versions, classifications or extractions here, but the document itself exists and
        // has been read by nothing: an empty list, not an invented page.
        mockMvc.perform(get("/api/v1/documents/{id}/pages", documentId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pages").isEmpty())
                .andExpect(jsonPath("$.pageCount").value(0))
                .andExpect(jsonPath("$.ocrPageCount").value(0));
    }

    @Test
    @DisplayName("every page says whether its text was read or recognised")
    void everyPageSaysWhereItsTextCameFrom() throws Exception {
        Session session = register();
        UUID documentId = versionedDocument(session);
        page(session, documentId, 1, "Der Betrag beläuft sich auf 119,00 Euro.", false);
        page(session, documentId, 2, "Erkannt durch Texterkennung", true);

        MvcResult listed = mockMvc.perform(get("/api/v1/documents/{id}/pages", documentId)
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageCount").value(2))
                .andExpect(jsonPath("$.ocrPageCount").value(1))
                .andReturn();

        // The first page came from the text layer and the second was recognised. A person confirming a
        // figure needs to know which of the two they are looking at: OCR is wrong in ways a text layer is
        // not, and the answer has to say so rather than presenting both as 'the letter says'.
        java.util.List<Boolean> ocr = JsonPath.read(
                listed.getResponse().getContentAsString(), "$.pages[*].ocrUsed");
        org.assertj.core.api.Assertions.assertThat(ocr).containsExactlyInAnyOrder(true, false);
    }

    @Test
    @DisplayName("a page with no text is served as an empty page rather than dropped")
    void anEmptyPageIsServedRatherThanDropped() throws Exception {
        Session session = register();
        UUID documentId = versionedDocument(session);
        page(session, documentId, 1, "Text", false);
        page(session, documentId, 2, "", false);

        // 'Read and found nothing' and 'not read' are different statements; a list that dropped the empty
        // ones would leave a person unable to tell a blank form from a page nobody looked at.
        mockMvc.perform(get("/api/v1/documents/{id}/pages", documentId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageCount").value(2))
                .andExpect(jsonPath("$.emptyPageCount").value(1));
    }

    // ---------------------------------------------------------------- what is refused

    @Test
    @DisplayName("without a session nothing is read")
    void withoutASessionNothingIsRead() throws Exception {
        mockMvc.perform(get("/api/v1/documents/extraction/supported-formats"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/documents/{id}/pages", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("another account's document yields no pages")
    void anotherAccountsDocumentYieldsNothing() throws Exception {
        Session first = register();
        Session second = register();
        UUID documentId = versionedDocument(first);
        page(first, documentId, 1, "Der Betrag beläuft sich auf 119,00 Euro.", false);

        MvcResult foreign = mockMvc.perform(get("/api/v1/documents/{id}/pages", documentId)
                        .cookie(second.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageCount").value(0))
                .andReturn();
        org.assertj.core.api.Assertions.assertThat(foreign.getResponse().getContentAsString())
                .as("nothing of the first account's page text may appear in the second's answer")
                .doesNotContain("119,00");
    }

    @Test
    @DisplayName("the document permissions exist and are held, which is what makes these reachable")
    void theDocumentPermissionsAreHeld() {
        Integer held = jdbc.queryForObject("""
                select count(*) from role_permissions rp
                join roles r on r.id = rp.role_id
                join permissions p on p.id = rp.permission_id
                where p.code in ('documents.view','documents.review')
                  and r.tenant_id is null and r.code in ('OWNER','ADMIN')
                """, Integer.class);

        org.assertj.core.api.Assertions.assertThat(held)
                .as("the document permissions must be granted to OWNER and ADMIN, or these are dead")
                .isGreaterThan(0);
    }
}
