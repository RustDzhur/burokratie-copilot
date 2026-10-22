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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import de.buerokratiecopilot.documents.DocumentExtractionRepository;
import de.buerokratiecopilot.documents.fields.FieldExtractionService;
import de.buerokratiecopilot.documents.pipeline.DocumentProcessingPipeline;
import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.support.TestDocuments;

/** BM-05: search and questions over the memory give sources, refuse without them, and never cross a tenant. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MemorySearchIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired
    private UserRepository users;
    @Autowired
    private DocumentExtractionRepository extractions;
    @Autowired
    private FieldExtractionService fieldExtraction;
    @Autowired
    private DocumentProcessingPipeline pipeline;

    private record Account(UUID tenantId, UUID userId, Cookie cookie) {
    }

    private Account newTenant() throws Exception {
        String email = "ask-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Ask Test","accountType":"COMPANY"}
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
        return new Account(tenantId, userId, login.getResponse().getCookie("bc_session"));
    }

    @Test
    @DisplayName("a question is answered with sources, a confirmed fact outranks and is marked, nothing found means no answer, another tenant finds nothing")
    void searchAndAsk() throws Exception {
        Account a = newTenant();
        Account b = newTenant();
        MvcResult upload = mockMvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "Heizkosten.pdf", "application/pdf", TestDocuments.pdf(
                                "Fernwärme Musterstadt GmbH\nRechnungsnummer: FW-2026-77\nRechnungsdatum: 15.03.2026\n"
                                        + "Zahlbar bis: 31.03.2026\nHeizkostenabrechnung\nNettobetrag: 1.000,00 EUR\n"
                                        + "Umsatzsteuer: 190,00 EUR\nBruttobetrag: 1.190,00 EUR\n")))
                        .with(csrf()).cookie(a.cookie()))
                .andExpect(status().isCreated()).andReturn();
        UUID documentId = UUID.fromString(JsonPath.read(upload.getResponse().getContentAsString(), "$.document.id"));
        RequestContext.runAs(a.tenantId(), () -> pipeline.advance(documentId));

        String search = mockMvc.perform(get("/api/v1/memory/search").param("q", "Heizkostenabrechnung").cookie(a.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((Integer) JsonPath.read(search, "$.found")).as(search).isGreaterThan(0);
        assertThat((Object) JsonPath.read(search, "$.hits[0].documentId")).isEqualTo(documentId.toString());
        assertThat((Object) JsonPath.read(search, "$.hits[0].citation")).asString().contains("Heizkosten.pdf");

        String ask = mockMvc.perform(post("/api/v1/memory/ask").with(csrf()).cookie(a.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Was kostet die Heizkostenabrechnung?\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((Object) JsonPath.read(ask, "$.kind")).as("no model is configured in the test").isEqualTo("SOURCES_ONLY");
        assertThat((java.util.List<?>) JsonPath.read(ask, "$.sources")).isNotEmpty();
        assertThat((Object) JsonPath.read(ask, "$.disclaimerRequired")).isEqualTo(false);

        String nothing = mockMvc.perform(post("/api/v1/memory/ask").with(csrf()).cookie(a.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"Zxqvbn Wrtplk Hjdsa\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((Object) JsonPath.read(nothing, "$.kind")).isEqualTo("NO_DATA");
        assertThat((java.util.List<?>) JsonPath.read(nothing, "$.sources")).isEmpty();

        mockMvc.perform(post("/api/v1/memory/ask").with(csrf()).cookie(a.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"  \"}"))
                .andExpect(status().isBadRequest());

        // A confirmed fact is marked as confirmed and ranked above the same value read by a machine.
        UUID factId = RequestContext.runAs(a.tenantId(), () -> extractions.findByDocumentIdOrderByFieldKeyAsc(documentId)
                .stream().filter(e -> "Rechnungsnummer".equals(e.getFieldKey()) || e.getFieldKey().toLowerCase().contains("rechnungsnummer"))
                .findFirst().orElseThrow().getId());
        RequestContext.runAs(a.tenantId(), () -> fieldExtraction.confirm(extractions.findById(factId).orElseThrow(), a.userId(),
                "FW-2026-77"));
        String confirmed = mockMvc.perform(get("/api/v1/memory/search").param("q", "FW-2026-77").cookie(a.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((java.util.List<?>) JsonPath.read(confirmed, "$.hits[?(@.kind == 'FACT' && @.verification == 'USER_CONFIRMED')]"))
                .isNotEmpty();

        String other = mockMvc.perform(get("/api/v1/memory/search").param("q", "Heizkostenabrechnung").cookie(b.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((Integer) JsonPath.read(other, "$.found")).isZero();
    }
}
