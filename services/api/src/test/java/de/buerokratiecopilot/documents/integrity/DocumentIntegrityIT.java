package de.buerokratiecopilot.documents.integrity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
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
import de.buerokratiecopilot.shared.storage.BlobStore;

/**
 * The archive can show that a file is what it was (spec §22).
 *
 * <p><strong>What was wrong.</strong> Every blob and every version carried a content hash, and nothing ever
 * compared one with the bytes. A hash that is written and never checked proves nothing about the file on disk
 * today — only about the file that was hashed on the day it arrived. For an archive meant to outlive the
 * software, that is the wrong half of the promise.</p>
 *
 * <p>The test stores real bytes through the real store, so the check runs against a file rather than a
 * column, and then makes the recorded hash disagree with them: a mismatch must be reported as a finding, not
 * repaired in silence.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentIntegrityIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private BlobStore blobs;

    private record Session(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private Session register() throws Exception {
        String email = "int-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Integrität Test","accountType":"COMPANY"}
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

    /** A document whose stored bytes are real; {@code recordedHash} is what the archive believes. */
    private UUID document(Session owner, byte[] content, String recordedHash) {
        return RequestContext.runAs(owner.tenantId(), () -> {
            BlobStore.StoredBlob stored = blobs.store(owner.tenantId(),
                    new ByteArrayInputStream(content), content.length, "text/plain");
            UUID documentId = jdbc.queryForObject("""
                    insert into documents (id, tenant_id, original_filename, internal_name, mime_type,
                                           size_bytes, content_hash, source, processing_status,
                                           review_status)
                    values (gen_random_uuid(), ?, 'notiz.txt', 'notiz.txt', 'text/plain', ?, ?,
                            'UPLOAD', 'READY', 'NOT_REQUIRED')
                    returning id
                    """, UUID.class, owner.tenantId(), content.length, stored.contentHash());
            UUID blobId = jdbc.queryForObject("""
                    insert into document_blobs (id, tenant_id, storage_key, storage_backend, size_bytes,
                                                content_hash, content_type, scan_status, quarantined)
                    values (gen_random_uuid(), ?, ?, ?, ?, ?, 'text/plain', 'CLEAN', false)
                    returning id
                    """, UUID.class, owner.tenantId(), stored.storageKey(), stored.backend(),
                    content.length, recordedHash);
            jdbc.update("""
                    insert into document_versions (id, document_id, tenant_id, version, blob_id,
                                                   content_hash, size_bytes, mime_type,
                                                   original_filename, created_by)
                    values (gen_random_uuid(), ?, ?, 1, ?, ?, ?, 'text/plain', 'notiz.txt', ?)
                    """, documentId, owner.tenantId(), blobId, recordedHash, content.length,
                    owner.userId());
            return documentId;
        });
    }

    @Test
    @DisplayName("a file that still matches its hash is reported as intact")
    void anIntactFileIsReportedAsIntact() throws Exception {
        Session owner = register();
        byte[] content = "Der Bericht ist unverändert.".getBytes(StandardCharsets.UTF_8);
        // The same hash the store computed, which is what the archive recorded when the file arrived.
        String realHash = RequestContext.runAs(owner.tenantId(), () -> {
            BlobStore.StoredBlob stored = blobs.store(owner.tenantId(),
                    new ByteArrayInputStream(content), content.length, "text/plain");
            return stored.contentHash();
        });
        UUID documentId = document(owner, content, realHash);

        mockMvc.perform(get("/api/v1/documents/{id}/integrity", documentId).cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.matches").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.version").value(1));
    }

    @Test
    @DisplayName("a file whose bytes changed is reported as a finding, not repaired")
    void aChangedFileIsReported() throws Exception {
        Session owner = register();
        byte[] content = "Der Bericht ist unverändert.".getBytes(StandardCharsets.UTF_8);
        // What the archive *believes* differs from what the bytes hash to.
        UUID documentId = document(owner, content,
                "0000000000000000000000000000000000000000000000000000000000000000");

        MvcResult result = mockMvc.perform(get("/api/v1/documents/{id}/integrity", documentId)
                        .cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.matches").value(false))
                .andReturn();

        // The actual hash is shown so that the finding can be investigated; the record is not rewritten.
        assertThat(result.getResponse().getContentAsString()).contains("\"actual\"");
        assertThat(jdbc.queryForObject(
                "select content_hash from document_versions where document_id = ?", String.class, documentId))
                .as("the check must never repair the record it is checking")
                .isEqualTo("0000000000000000000000000000000000000000000000000000000000000000");
    }

    @Test
    @DisplayName("another account cannot check this document")
    void anotherAccountCannotCheckIt() throws Exception {
        Session first = register();
        Session second = register();
        UUID documentId = document(first, "Inhalt".getBytes(StandardCharsets.UTF_8),
                "1111111111111111111111111111111111111111111111111111111111111111");

        mockMvc.perform(get("/api/v1/documents/{id}/integrity", documentId).cookie(second.cookie()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("without a session nothing is checked")
    void withoutASessionNothingIsChecked() throws Exception {
        mockMvc.perform(get("/api/v1/documents/{id}/integrity", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
