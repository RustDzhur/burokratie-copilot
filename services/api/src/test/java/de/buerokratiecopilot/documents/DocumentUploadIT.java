package de.buerokratiecopilot.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * End-to-end verification of document receipt (spec §28.3 scenario 2 and 4, §6.2, §9.1).
 *
 * <p>The test uploads real bytes through the real HTTP stack: the file is written to the object
 * store, hashed, recorded in PostgreSQL and announced through the outbox. The assertions cover the
 * stored metadata, duplicate detection and tenant isolation — the three properties that make the
 * inbox trustworthy.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentUploadIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private DocumentRepository documents;

    /**
     * A valid PDF. The previous fixture was a byte string that only started with "%PDF-"; it was
     * enough while nothing parsed the file, and became a failure as soon as text extraction did.
     */
    private static byte[] invoicePdf(String marker) {
        try {
            return de.buerokratiecopilot.support.TestDocuments.pdf("Rechnung " + marker);
        }
        catch (java.io.IOException ex) {
            throw new IllegalStateException("test fixture could not be built", ex);
        }
    }

    private record Session(Cookie cookie, String tenantId) {
    }

    private Session signIn(String prefix) throws Exception {
        String email = prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Dokumente Test","accountType":"COMPANY"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();

        String body = registration.getResponse().getContentAsString();
        String userId = JsonPath.read(body, "$.userId");
        String tenantId = JsonPath.read(body, "$.tenantId");

        RequestContext.runAs(UUID.fromString(tenantId), () -> {
            User user = users.findById(UUID.fromString(userId)).orElseThrow();
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

        return new Session(login.getResponse().getCookie("bc_session"), tenantId);
    }

    private MvcResult upload(Session session, String filename, byte[] content, int expectedStatus)
            throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", filename, "application/pdf", content);
        return mockMvc.perform(multipart("/api/v1/documents").file(file).with(csrf())
                        .cookie(session.cookie()))
                .andExpect(status().is(expectedStatus))
                .andReturn();
    }

    @Test
    @DisplayName("an uploaded document is stored, hashed and recorded in the inbox")
    void uploadCreatesDocumentWithMetadata() throws Exception {
        Session session = signIn("upload");
        byte[] content = invoicePdf("rechnung-1");

        MvcResult result = upload(session, "Rechnung 2026-001.pdf", content, 201);
        String body = result.getResponse().getContentAsString();

        assertThat(JsonPath.<Boolean>read(body, "$.duplicate")).isFalse();
        assertThat(JsonPath.<String>read(body, "$.document.processingStatus")).isEqualTo("RECEIVED");
        assertThat(JsonPath.<String>read(body, "$.document.originalFilename"))
                .isEqualTo("Rechnung 2026-001.pdf");
        assertThat(JsonPath.<Integer>read(body, "$.document.sizeBytes")).isEqualTo(content.length);

        String documentId = JsonPath.read(body, "$.document.id");
        Document stored = documents.findById(UUID.fromString(documentId)).orElseThrow();

        assertThat(stored.getContentHash()).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(stored.getCurrentVersion()).isEqualTo(1);
        assertThat(stored.getSource()).isEqualTo(Document.Source.UPLOAD);
        assertThat(stored.getTenantId()).isEqualTo(UUID.fromString(session.tenantId()));
        assertThat(stored.getDuplicateOfDocumentId()).isNull();

        // The stored bytes are reachable and identical.
        byte[] downloaded = mockMvc.perform(get("/api/v1/documents/{id}/content", documentId)
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(downloaded).isEqualTo(content);
    }

    @Test
    @DisplayName("uploading the same content twice is reported as a duplicate, not as a new document")
    void duplicateContentIsDetected() throws Exception {
        Session session = signIn("dupe");
        byte[] content = invoicePdf("identisch");

        String firstId = JsonPath.read(upload(session, "Rechnung.pdf", content, 201)
                .getResponse().getContentAsString(), "$.document.id");

        MvcResult second = upload(session, "Rechnung-kopie.pdf", content, 201);
        String body = second.getResponse().getContentAsString();

        assertThat(JsonPath.<Boolean>read(body, "$.duplicate")).isTrue();
        assertThat(JsonPath.<String>read(body, "$.duplicateOf")).isEqualTo(firstId);
        assertThat(JsonPath.<String>read(body, "$.document.processingStatus")).isEqualTo("DUPLICATE");
        assertThat(JsonPath.<String>read(body, "$.document.duplicateOfDocumentId")).isEqualTo(firstId);

        // "Process again" on a duplicate must leave it a duplicate, not fail trying to move it on.
        String duplicateId = JsonPath.read(body, "$.document.id");
        MvcResult again = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/documents/{id}/reprocess", duplicateId)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(JsonPath.<String>read(again.getResponse().getContentAsString(), "$.processingStatus"))
                .isEqualTo("DUPLICATE");
        mockMvc.perform(get("/api/v1/documents/{id}/pipeline", duplicateId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.steps[?(@.status == 'FAILED')]").isEmpty());
    }

    @Test
    @DisplayName("a deleted document is gone for its owner, and the same file can be uploaded again as a new one")
    void deletedDocumentDisappears() throws Exception {
        Session session = signIn("delete");
        byte[] content = invoicePdf("zu-loeschen");
        String id = JsonPath.read(upload(session, "Rechnung.pdf", content, 201)
                .getResponse().getContentAsString(), "$.document.id");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/documents/{id}", id)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .cookie(session.cookie()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/documents/{id}", id).cookie(session.cookie()))
                .andExpect(status().isNotFound());

        String again = upload(session, "Rechnung.pdf", content, 201).getResponse().getContentAsString();
        assertThat(JsonPath.<Boolean>read(again, "$.duplicate")).isFalse();
    }

    @Test
    @DisplayName("several documents are deleted at once; one that is not found is reported as refused, not fatal")
    void bulkDelete() throws Exception {
        Session session = signIn("bulk");
        String a = JsonPath.read(upload(session, "a.pdf", invoicePdf("bulk-a"), 201)
                .getResponse().getContentAsString(), "$.document.id");
        String b = JsonPath.read(upload(session, "b.pdf", invoicePdf("bulk-b"), 201)
                .getResponse().getContentAsString(), "$.document.id");
        String missing = UUID.randomUUID().toString();

        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/documents/bulk-delete")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .cookie(session.cookie())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[\"" + a + "\",\"" + b + "\",\"" + missing + "\"]}"))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(JsonPath.<Integer>read(body, "$.deleted")).isEqualTo(2);
        assertThat(JsonPath.<java.util.List<String>>read(body, "$.refused")).containsExactly(missing);
        mockMvc.perform(get("/api/v1/documents/{id}", a).cookie(session.cookie())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/documents/{id}", b).cookie(session.cookie())).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a document of another tenant does not exist from this session's point of view")
    void crossTenantAccessIsRefused() throws Exception {
        Session tenantA = signIn("tenant-a");
        Session tenantB = signIn("tenant-b");

        String documentOfA = JsonPath.read(
                upload(tenantA, "geheim.pdf", invoicePdf("a-only"), 201)
                        .getResponse().getContentAsString(),
                "$.document.id");

        // The owner sees it.
        mockMvc.perform(get("/api/v1/documents/{id}", documentOfA).cookie(tenantA.cookie()))
                .andExpect(status().isOk());

        // Another tenant gets 404, not 403: even the existence of the identifier stays hidden.
        mockMvc.perform(get("/api/v1/documents/{id}", documentOfA).cookie(tenantB.cookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        mockMvc.perform(get("/api/v1/documents/{id}/content", documentOfA).cookie(tenantB.cookie()))
                .andExpect(status().isNotFound());

        // And the listing of the other tenant does not contain it.
        String listing = mockMvc.perform(get("/api/v1/documents").cookie(tenantB.cookie()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(listing).doesNotContain(documentOfA);
    }

    @Test
    @DisplayName("an impossible status change is refused instead of corrupting the pipeline")
    void invalidStatusTransitionIsRefused() throws Exception {
        Session session = signIn("status");
        String documentId = JsonPath.read(
                upload(session, "dok.pdf", invoicePdf("status"), 201)
                        .getResponse().getContentAsString(),
                "$.document.id");

        // RECEIVED -> COMPLETED skips the whole pipeline and must not be possible.
        mockMvc.perform(post("/api/v1/documents/{id}/status", documentId)
                        .param("status", "COMPLETED").with(csrf()).cookie(session.cookie()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));

        // The valid next step works.
        mockMvc.perform(post("/api/v1/documents/{id}/status", documentId)
                        .param("status", "QUARANTINED").with(csrf()).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processingStatus").value("QUARANTINED"));
    }

    @Test
    @DisplayName("a file whose content contradicts its name never enters the inbox")
    void rejectedContentDoesNotEnterTheInbox() throws Exception {
        Session session = signIn("reject");
        byte[] script = "#!/bin/sh\necho nicht wirklich ein PDF\n".getBytes(StandardCharsets.UTF_8);

        MvcResult result = upload(session, "Rechnung.pdf", script, 422);
        assertThat(result.getResponse().getContentAsString()).contains("FILE_REJECTED");

        // Nothing was recorded, and the inbox stayed empty for this tenant.
        String listing = mockMvc.perform(get("/api/v1/documents").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(listing).contains("\"totalElements\":0");
    }

    @Test
    @DisplayName("upload without a session is rejected")
    void uploadRequiresAuthentication() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "x.pdf", "application/pdf",
                invoicePdf("anonymous"));
        mockMvc.perform(multipart("/api/v1/documents").file(file).with(csrf()))
                .andExpect(status().isUnauthorized());
    }
}
