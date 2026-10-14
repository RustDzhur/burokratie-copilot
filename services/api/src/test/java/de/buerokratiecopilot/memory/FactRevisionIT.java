package de.buerokratiecopilot.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import jakarta.servlet.http.Cookie;

import com.jayway.jsonpath.JsonPath;

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

import de.buerokratiecopilot.documents.DocumentExtractionRepository;
import de.buerokratiecopilot.documents.domain.DocumentExtraction;
import de.buerokratiecopilot.documents.fields.FieldExtractionService;
import de.buerokratiecopilot.documents.pipeline.DocumentProcessingPipeline;
import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.support.TestDocuments;

/** BM-03: a changed fact keeps what it said before; a confirmed fact is told apart from a machine-read one. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FactRevisionIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private UserRepository users;
    @Autowired
    private DocumentExtractionRepository extractions;
    @Autowired
    private FieldExtractionService fieldExtraction;
    @Autowired
    private DocumentProcessingPipeline pipeline;

    private record Session(UUID tenantId, UUID userId, Cookie cookie) {
    }

    private Session newTenant() throws Exception {
        String email = "fact-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Fact Test","accountType":"COMPANY"}
                                """.formatted(email)))
                .andExpect(status().isCreated()).andReturn();
        UUID tenantId = UUID.fromString(JsonPath.read(registration.getResponse().getContentAsString(), "$.tenantId"));
        UUID userId = UUID.fromString(JsonPath.read(registration.getResponse().getContentAsString(), "$.userId"));
        RequestContext.runAs(tenantId, () -> {
            User user = users.findById(userId).orElseThrow();
            user.markEmailVerified(java.time.Instant.now());
            user.setStatus(User.Status.ACTIVE);
            return users.save(user);
        });
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"EinSicheresPasswort-2026!\"}".formatted(email)))
                .andExpect(status().isOk()).andReturn();
        return new Session(tenantId, userId, login.getResponse().getCookie("bc_session"));
    }

    @Test
    @DisplayName("a correction keeps the old value and who changed it; the confirmed fact is told apart; another tenant sees nothing")
    void historyAndVerification() throws Exception {
        Session a = newTenant();
        Session b = newTenant();
        MvcResult upload = mockMvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "Rechnung.pdf", "application/pdf", TestDocuments.pdf(
                                "Muster GmbH\nRechnungsnummer: 2026-501\nRechnungsdatum: 15.03.2026\nZahlbar bis: 31.03.2026\n"
                                        + "Nettobetrag: 1.000,00 EUR\nUmsatzsteuer: 190,00 EUR\nBruttobetrag: 1.190,00 EUR\n")))
                        .with(csrf()).cookie(a.cookie()))
                .andExpect(status().isCreated()).andReturn();
        UUID documentId = UUID.fromString(JsonPath.read(upload.getResponse().getContentAsString(), "$.document.id"));
        RequestContext.runAs(a.tenantId(), () -> pipeline.advance(documentId));

        String facts = mockMvc.perform(get("/api/v1/memory/documents/" + documentId + "/facts").cookie(a.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((java.util.List<?>) JsonPath.read(facts, "$")).isNotEmpty();
        assertThat((java.util.List<?>) JsonPath.read(facts, "$[?(@.verification != 'UNVERIFIED')]"))
                .as("what a machine read is not confirmed").isEmpty();

        DocumentExtraction first = RequestContext.runAs(a.tenantId(),
                () -> extractions.findByDocumentIdOrderByFieldKeyAsc(documentId).get(0));
        UUID factId = first.getId();
        String original = first.getFieldValue();

        RequestContext.runAs(a.tenantId(), () -> fieldExtraction.confirm(
                extractions.findById(factId).orElseThrow(), a.userId(), "korrigiert-1"));
        RequestContext.runAs(a.tenantId(), () -> fieldExtraction.confirm(
                extractions.findById(factId).orElseThrow(), a.userId(), "korrigiert-2"));

        String revisions = mockMvc.perform(get("/api/v1/memory/facts/" + factId + "/revisions").cookie(a.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((java.util.List<?>) JsonPath.read(revisions, "$")).hasSize(2);
        assertThat((Object) JsonPath.read(revisions, "$[0].previous_value")).isEqualTo("korrigiert-1");
        assertThat((Object) JsonPath.read(revisions, "$[0].new_value")).isEqualTo("korrigiert-2");
        assertThat((Object) JsonPath.read(revisions, "$[0].reason")).isEqualTo("USER_CORRECTION");
        assertThat((Object) JsonPath.read(revisions, "$[0].changed_by")).isEqualTo(a.userId().toString());
        assertThat((Object) JsonPath.read(revisions, "$[1].previous_value")).isEqualTo(original);
        assertThat((Object) JsonPath.read(revisions, "$[0].supersedes_revision_id")).isEqualTo(JsonPath.read(revisions, "$[1].id"));

        String after = mockMvc.perform(get("/api/v1/memory/documents/" + documentId + "/facts").cookie(a.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((java.util.List<?>) JsonPath.read(after, "$[?(@.id == '" + factId + "' && @.verification == 'USER_CONFIRMED')]"))
                .hasSize(1);

        mockMvc.perform(get("/api/v1/memory/facts/" + factId + "/revisions").cookie(b.cookie()))
                .andExpect(status().isNotFound());
        assertThat(mockMvc.perform(get("/api/v1/memory/documents/" + documentId + "/facts").cookie(b.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).isEqualTo("[]");
    }
}
