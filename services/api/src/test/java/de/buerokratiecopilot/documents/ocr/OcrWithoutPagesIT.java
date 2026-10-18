package de.buerokratiecopilot.documents.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.jayway.jsonpath.JsonPath;

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

import de.buerokratiecopilot.documents.pipeline.DocumentProcessingPipeline;
import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * A document with no pages asks a person instead of reporting success (spec §9.1).
 *
 * <p><strong>What this covers.</strong> The OCR step used to answer "Every page carries a text layer" when it
 * found nothing to do — and with zero stored pages that is a true statement about nothing. The extraction had
 * produced nothing to look at, and the history said the step succeeded, so "nothing to do" and "nothing
 * worked" looked identical.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OcrWithoutPagesIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DocumentProcessingPipeline pipeline;

    private UUID registerAndMakeDocumentWithoutPages() throws Exception {
        String email = "nopg-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Seitenlos Test","accountType":"COMPANY"}
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

        return RequestContext.runAs(tenantId, () -> {
            UUID documentId = jdbc.queryForObject("""
                    insert into documents (id, tenant_id, original_filename, internal_name, mime_type,
                                           size_bytes, content_hash, source, processing_status,
                                           review_status)
                    values (gen_random_uuid(), ?, 'leer.pdf', 'leer.pdf', 'application/pdf', 4, ?,
                            'UPLOAD', 'OCR_PENDING', 'NOT_REQUIRED')
                    returning id
                    """, UUID.class, tenantId,
                    "%064x".formatted(java.util.concurrent.ThreadLocalRandom.current().nextLong()));
            // A version with a blob and deliberately no page: the extraction produced nothing to look at.
            UUID blobId = jdbc.queryForObject("""
                    insert into document_blobs (id, tenant_id, storage_key, size_bytes, content_hash,
                                                content_type, scan_status, quarantined)
                    values (gen_random_uuid(), ?, ?, 4, ?, 'application/pdf', 'CLEAN', false)
                    returning id
                    """, UUID.class, tenantId, "tenants/" + tenantId + "/" + UUID.randomUUID() + ".pdf",
                    "%064x".formatted(java.util.concurrent.ThreadLocalRandom.current().nextLong()));
            jdbc.update("""
                    insert into document_versions (id, document_id, tenant_id, version, blob_id,
                                                   content_hash, size_bytes, mime_type,
                                                   original_filename, created_by)
                    values (gen_random_uuid(), ?, ?, 1, ?, ?, 4, 'application/pdf', 'leer.pdf', ?)
                    """, documentId, tenantId, blobId,
                    "%064x".formatted(java.util.concurrent.ThreadLocalRandom.current().nextLong()),
                    userId);
            return documentId;
        });
    }

    @Test
    @DisplayName("a document with no pages goes to review instead of reporting success")
    void noPagesGoesToReview() throws Exception {
        UUID documentId = registerAndMakeDocumentWithoutPages();

        var result = RequestContext.runAs(
                jdbc.queryForObject("select tenant_id from documents where id = ?", UUID.class, documentId),
                () -> pipeline.advance(documentId));

        // The status is what a person sees. "READY" would mean the document was understood; nothing was read.
        assertThat(String.valueOf(result.status())).isNotEqualTo("READY");
        String status = jdbc.queryForObject("select processing_status from documents where id = ?",
                String.class, documentId);
        assertThat(status).as("nothing was read, so nothing may look finished").isNotEqualTo("READY");
    }
}
