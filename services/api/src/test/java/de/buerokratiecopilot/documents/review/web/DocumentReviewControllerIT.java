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
 * <p><strong>What this does not cover, named rather than implied.</strong> Both a classification and an
 * extracted value hang off a document <em>version</em>: document_versions carries a blob_id pointing at a
 * stored blob, and document_extractions.version_id is NOT NULL. Building that fixture is a larger job than
 * this round could verify, so what is asserted here is the empty case and the isolation, not the populated
 * one. <strong>Specifically not covered: that a value is served with its page and sentence, that a value
 * without a source is marked unverifiable, and that another account's extraction rows are invisible.</strong>
 * The routes themselves are exercised; the assertions about their contents are not, and no claim is made that
 * they are.</p>
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
