package de.buerokratiecopilot.documents.review.web;

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
 * Classifications and extracted values over HTTP (spec §14, §15; B-008, B-009).
 *
 * <p>What this checks beyond the routes: an extracted value is served <strong>with the page and the sentence
 * it came from</strong>, and a value that has neither is <strong>marked as unverifiable rather than presented
 * alike</strong> — a figure somebody is asked to confirm has to be checkable against the letter, and one that
 * is not is a figure they must take on trust.</p>
 *
 * <p><strong>The schema enforces provenance, and that shaped these tests.</strong>
 * {@code document_extractions_provenance_chk} requires an extraction to carry a snippet unless it was entered
 * by a person or linked to an object. A value nobody can check against the letter is therefore only ever a
 * MANUAL one, and the assertion that a value is marked unverifiable uses that case rather than inventing one
 * the database would reject.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentReviewControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    private record Session(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private Session register() throws Exception {
        String email = "rev-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Prüfung Test","accountType":"COMPANY"}
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
     * A document with one stored version.
     *
     * <p>A version needs a blob — {@code document_versions.blob_id} is a foreign key to
     * {@code document_blobs} — and both a classification and an extracted value hang off the version, which
     * is why neither could be exercised before this fixture existed.</p>
     */
    private UUID versionedDocument(Session session) {
        UUID documentId = documentFor(session);
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

    private void classificationRow(Session session, UUID documentId, String method, boolean current,
                                   String confidence) {
        RequestContext.runAs(session.tenantId(), () -> jdbc.update("""
                insert into document_classifications (id, document_id, version_id, document_type_id,
                                                      confidence, method, rationale, is_current, created_by)
                select gen_random_uuid(), ?, v.id, dt.id, ?::numeric, ?, ?, ?, ?
                  from document_versions v, document_types dt
                 where v.document_id = ? and dt.code = 'rechnung'
                 limit 1
                """, documentId, confidence, method, "Weil der Absender bekannt ist", current,
                session.userId(), documentId));
    }

    /**
     * An extracted value. The provenance check in the schema decides what is allowed here: an extraction must
     * carry a snippet, unless a person entered it or it is linked to an object. That means a value with no
     * source can only be a MANUAL one — which is exactly the semantics, and the reason the assertion below
     * uses that case.
     */
    private void extractionRow(UUID documentId, String key, String value, String extractor,
                               String sourceKind, Integer page, String snippet, java.util.UUID tenantId) {
        RequestContext.runAs(tenantId, () -> jdbc.update("""
                insert into document_extractions (id, document_id, version_id, field_key, field_value,
                                                  normalized_value, value_type, confidence, source_kind,
                                                  source_page, source_snippet, extractor)
                select gen_random_uuid(), ?, v.id, ?, ?, ?, 'MONEY', 0.900, ?, ?, ?, ?
                  from document_versions v
                 where v.document_id = ?
                 limit 1
                """, documentId, key, value, value, sourceKind, page, snippet, extractor, documentId));
    }

    private UUID documentFor(Session session) {
        return jdbc.queryForObject("""
                insert into documents (id, tenant_id, original_filename, internal_name, mime_type,
                                       size_bytes, content_hash, source, processing_status, review_status)
                values (gen_random_uuid(), ?, 'akte.pdf', 'akte.pdf', 'application/pdf', 10, ?,
                        'UPLOAD', 'READY', 'NOT_REQUIRED')
                returning id
                """, UUID.class, session.tenantId(),
                "%064x".formatted(java.util.concurrent.ThreadLocalRandom.current().nextLong()));
    }

    // ---------------------------------------------------------------- with a stored version

    @Test
    @DisplayName("a value is served with the page and sentence it came from")
    void aValueIsServedWithItsSource() throws Exception {
        Session session = register();
        UUID documentId = versionedDocument(session);
        extractionRow(documentId, "betrag", "119,00", "RULE", "TEXT", 3,
                "Der Betrag beläuft sich auf 119,00 Euro.", session.tenantId());

        mockMvc.perform(get("/api/v1/documents/{id}/fields", documentId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields[0].fieldKey").value("betrag"))
                .andExpect(jsonPath("$.fields[0].rawValue").value("119,00"))
                .andExpect(jsonPath("$.fields[0].sourcePage").value(3))
                .andExpect(jsonPath("$.fields[0].sourceSnippet").isNotEmpty())
                .andExpect(jsonPath("$.fields[0].verifiable").value(true))
                .andExpect(jsonPath("$.unverifiableCount").value(0));
    }

    @Test
    @DisplayName("a value a person entered has no page to point at, and is marked as such")
    void aManualValueIsMarkedUnverifiable() throws Exception {
        Session session = register();
        UUID documentId = versionedDocument(session);
        // The schema's provenance check enforces this shape: only a MANUAL extraction or a linked object may
        // omit the snippet. A value nobody can check against the letter is one somebody must trust, and the
        // answer says which of the two it is rather than presenting them alike.
        extractionRow(documentId, "aktenzeichen", "AZ-1", "MANUAL", "MANUAL", null, null,
                session.tenantId());

        mockMvc.perform(get("/api/v1/documents/{id}/fields", documentId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields[0].verifiable").value(false))
                .andExpect(jsonPath("$.unverifiableCount").value(1));
    }

    @Test
    @DisplayName("another account sees nothing of the first account's document")
    void anotherAccountSeesNothing() throws Exception {
        Session first = register();
        Session second = register();
        UUID documentId = versionedDocument(first);
        extractionRow(documentId, "betrag", "119,00", "RULE", "TEXT", 1,
                "Der Betrag beläuft sich auf 119,00 Euro.", first.tenantId());

        mockMvc.perform(get("/api/v1/documents/{id}/fields", documentId).cookie(first.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields[0].rawValue").value("119,00"));

        MvcResult foreign = mockMvc.perform(get("/api/v1/documents/{id}/fields", documentId)
                        .cookie(second.cookie()))
                .andExpect(status().isOk())
                .andReturn();
        org.assertj.core.api.Assertions.assertThat(foreign.getResponse().getContentAsString())
                .as("nothing of the first account's document may appear in the second's answer")
                .doesNotContain("119,00");
    }

    @Test
    @DisplayName("the history says which entry counts, as a property of the row and not of its place")
    void theHistorySaysWhichEntryCounts() throws Exception {
        Session session = register();
        UUID documentId = versionedDocument(session);
        classificationRow(session, documentId, "RULE", false, "0.42");
        classificationRow(session, documentId, "MANUAL", true, "1.00");

        mockMvc.perform(get("/api/v1/documents/{id}/classifications", documentId)
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.current == true)].rationale").isNotEmpty());
    }

    @Test
    @DisplayName("a document nobody has decided about is not given a classification")
    void anUndecidedDocumentIsNotGivenOne() throws Exception {
        Session session = register();

        mockMvc.perform(get("/api/v1/documents/{id}/classifications", UUID.randomUUID())
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("the review threshold is served with what it means")
    void theReviewThresholdIsServed() throws Exception {
        Session session = register();

        mockMvc.perform(get("/api/v1/documents/classification/review-threshold")
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewThreshold").isNumber())
                .andExpect(jsonPath("$.note").isNotEmpty());
    }



    @Test
    @DisplayName("a document with nothing extracted is not given any values")
    void aDocumentWithNoValuesSaysSo() throws Exception {
        Session session = register();
        UUID documentId = documentFor(session);

        mockMvc.perform(get("/api/v1/documents/{id}/fields", documentId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields").isEmpty())
                .andExpect(jsonPath("$.unverifiableCount").value(0));
    }


    @Test
    @DisplayName("without a session nothing is read")
    void withoutASessionNothingIsRead() throws Exception {
        mockMvc.perform(get("/api/v1/documents/{id}/classifications", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/documents/{id}/fields", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/documents/classification/review-threshold"))
                .andExpect(status().isUnauthorized());
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
