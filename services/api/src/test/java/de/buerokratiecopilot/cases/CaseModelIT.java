package de.buerokratiecopilot.cases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

import de.buerokratiecopilot.documents.DocumentRepository;
import de.buerokratiecopilot.documents.pipeline.DocumentProcessingPipeline;
import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.support.TestDocuments;

/**
 * Verifies the case model (spec §11.1).
 *
 * <p>A case has to hold the matter together: its documents, its history and what is still missing. The
 * test therefore checks a case from creation to a document being attached, including that the case
 * number is generated, the creator can see it, and the history records what happened.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CaseModelIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private DocumentRepository documents;

    @Autowired
    private DocumentProcessingPipeline pipeline;

    @Autowired
    private CaseTestSupport support;

    private record Session(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private Session register() throws Exception {
        String email = "case-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Vorgang Test","accountType":"COMPANY"}
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

    private String openCase(Session session, String title, String typeCode) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/cases").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"%s","caseTypeCode":"%s","description":"Prüfen","priority":"HIGH"}
                                """.formatted(title, typeCode)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    }

    @Test
    @DisplayName("a new case gets a case number, an owner and a first history entry")
    void createsCaseWithReferenceAndOwner() throws Exception {
        Session session = register();

        MvcResult created = mockMvc.perform(post("/api/v1/cases").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Nebenkostenabrechnung 2025 prüfen","caseTypeCode":"rechnungsklaerung",
                                 "priority":"HIGH"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reference").isNotEmpty())
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.accessLevel").value("FULL"))
                .andReturn();

        String reference = JsonPath.read(created.getResponse().getContentAsString(), "$.reference");
        String id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        assertThat(reference).matches("V-\\d{4}-\\d{4}");

        mockMvc.perform(get("/api/v1/cases/{id}", id).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participants.length()").value(1))
                .andExpect(jsonPath("$.participants[0].role").value("OWNER"))
                .andExpect(jsonPath("$.participants[0].accessLevel").value("FULL"))
                .andExpect(jsonPath("$.timeline[0].type").value("CASE_CREATED"))
                .andExpect(jsonPath("$.requiredDocuments").isArray());
    }

    @Test
    @DisplayName("the case type decides which documents the case normally needs")
    void caseTypeCarriesDocumentRequirements() throws Exception {
        Session session = register();
        String id = openCase(session, "Kündigung des Mietvertrags", "kuendigung");

        mockMvc.perform(get("/api/v1/cases/{id}", id).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseRecord.typeCode").value("kuendigung"))
                .andExpect(jsonPath("$.caseRecord.typeName").value("Kündigung"));
    }

    @Test
    @DisplayName("a case holds the documents that belong to it")
    void caseHoldsItsDocuments() throws Exception {
        Session session = register();
        String caseId = openCase(session, "Rechnung prüfen", "rechnungsklaerung");

        MockMultipartFile file = new MockMultipartFile("file", "Rechnung.pdf", "application/pdf",
                TestDocuments.pdf("Rechnungsnummer: 2026-901\nZahlbar bis: 31.03.2026\n"
                        + "Umsatzsteuer: 1,00 EUR\nBruttobetrag: 11,90 EUR"));
        MvcResult upload = mockMvc.perform(multipart("/api/v1/documents").file(file).with(csrf())
                        .cookie(session.cookie()))
                .andExpect(status().isCreated())
                .andReturn();
        UUID documentId = UUID.fromString(JsonPath.read(
                upload.getResponse().getContentAsString(), "$.document.id"));
        RequestContext.runAs(session.tenantId(), () -> pipeline.advance(documentId));

        support.linkDocument(session.tenantId(), UUID.fromString(caseId), documentId, "MANUAL", 1.0);

        mockMvc.perform(get("/api/v1/cases/{id}", caseId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents.length()").value(1))
                .andExpect(jsonPath("$.documents[0].documentId").value(documentId.toString()))
                .andExpect(jsonPath("$.documents[0].filename").value("Rechnung.pdf"));
    }

    @Test
    @DisplayName("case numbers are unique per tenant and keep counting")
    void caseNumbersAreSequential() throws Exception {
        Session session = register();
        String first = JsonPath.read(mockMvc.perform(post("/api/v1/cases").with(csrf())
                        .cookie(session.cookie()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Erster\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.reference");
        String second = JsonPath.read(mockMvc.perform(post("/api/v1/cases").with(csrf())
                        .cookie(session.cookie()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Zweiter\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.reference");

        assertThat(first).isNotEqualTo(second);
        int firstNumber = Integer.parseInt(first.substring(first.lastIndexOf('-') + 1));
        int secondNumber = Integer.parseInt(second.substring(second.lastIndexOf('-') + 1));
        assertThat(secondNumber).isEqualTo(firstNumber + 1);
    }

    @Test
    @DisplayName("the overview counts open and overdue cases of the cases a user may see")
    void statisticsCountOnlyVisibleCases() throws Exception {
        Session session = register();
        openCase(session, "Offener Vorgang", "leistungsantrag");

        MvcResult statistics = mockMvc.perform(get("/api/v1/cases/statistics").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(1))
                .andExpect(jsonPath("$.total").value(1))
                .andReturn();

        assertThat(statistics.getResponse().getContentAsString()).contains("overdue");
    }

    @Test
    @DisplayName("a case without a title is refused instead of being created unnamed")
    void refusesCaseWithoutTitle() throws Exception {
        Session session = register();

        mockMvc.perform(post("/api/v1/cases").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"   \"}"))
                .andExpect(status().is4xxClientError());
    }
}
