package de.buerokratiecopilot.templates.web;

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
 * A generated document can be downloaded (spec §15).
 *
 * <p><strong>What was wrong.</strong> A generated letter was rendered, stored as a blob and recorded with its
 * {@code rendered_blob_id} — and nothing ever read that column. The document existed inside the system and
 * could not be obtained from it, which is the entire reason for generating it. The neighbouring
 * {@code GeneratedDocumentExportService} was also called by nothing; it builds a file while generating, this
 * route serves the file that was built.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GeneratedDocumentDownloadIT {

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
        String email = "gen-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Dokument Test","accountType":"COMPANY"}
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

    /** A generated document whose produced file really exists. */
    private UUID generated(Session owner, String title, byte[] content) {
        return RequestContext.runAs(owner.tenantId(), () -> {
            BlobStore.StoredBlob stored = blobs.store(owner.tenantId(),
                    new ByteArrayInputStream(content), content.length, "application/pdf");
            UUID blobId = jdbc.queryForObject("""
                    insert into document_blobs (id, tenant_id, storage_key, storage_backend, size_bytes,
                                                content_hash, content_type, scan_status, quarantined)
                    values (gen_random_uuid(), ?, ?, ?, ?, ?, 'application/pdf', 'CLEAN', false)
                    returning id
                    """, UUID.class, owner.tenantId(), stored.storageKey(), stored.backend(),
                    content.length, stored.contentHash());
            return jdbc.queryForObject("""
                    insert into generated_documents (id, tenant_id, title, output_format, status,
                                                     field_values, unresolved_fields, rendered_blob_id)
                    values (gen_random_uuid(), ?, ?, 'PDF', 'GENERATED', '{}'::jsonb, '[]'::jsonb, ?)
                    returning id
                    """, UUID.class, owner.tenantId(), title, blobId);
        });
    }

    @Test
    @DisplayName("the produced file of a generated document can be downloaded")
    void theProducedFileCanBeDownloaded() throws Exception {
        Session owner = register();
        byte[] content = "%PDF-1.4 ein erzeugtes Schreiben".getBytes(StandardCharsets.UTF_8);
        UUID documentId = generated(owner, "Anschreiben Muster", content);

        MvcResult result = mockMvc.perform(get("/api/v1/generated-documents/{id}/download", documentId)
                        .cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(content);
        // A file the browser saves needs a name; one called "document" helps nobody.
        assertThat(result.getResponse().getHeader("Content-Disposition")).contains("Anschreiben");
    }

    @Test
    @DisplayName("another account cannot download the generated document")
    void anotherAccountCannotDownloadIt() throws Exception {
        Session first = register();
        Session second = register();
        UUID documentId = generated(first, "Anschreiben", "Inhalt".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(get("/api/v1/generated-documents/{id}/download", documentId)
                        .cookie(second.cookie()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a document without a produced file says so instead of returning an empty download")
    void withoutAFileItSaysSo() throws Exception {
        Session owner = register();
        UUID documentId = RequestContext.runAs(owner.tenantId(), () -> jdbc.queryForObject("""
                insert into generated_documents (id, tenant_id, title, output_format, status,
                                                 field_values, unresolved_fields)
                values (gen_random_uuid(), ?, 'Ohne Datei', 'PDF', 'DRAFT', '{}'::jsonb, '[]'::jsonb)
                returning id
                """, UUID.class, owner.tenantId()));

        mockMvc.perform(get("/api/v1/generated-documents/{id}/download", documentId)
                        .cookie(owner.cookie()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("without a session nothing is downloaded")
    void withoutASessionNothingIsDownloaded() throws Exception {
        mockMvc.perform(get("/api/v1/generated-documents/{id}/download", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
