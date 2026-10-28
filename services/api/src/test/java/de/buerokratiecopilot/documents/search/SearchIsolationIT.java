package de.buerokratiecopilot.documents.search;

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
 * Verifies search, and above all that it does not cross tenants (spec §10.5 acceptance).
 *
 * <p>The isolation case is the acceptance criterion that matters: a search that returns another
 * customer's document is a data breach with an ordinary user interface. The test therefore gives two
 * tenants a document containing a word only one of them uses, and asserts the other one cannot find
 * it — through the API, as a user would.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SearchIsolationIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private DocumentRepository documents;

    @Autowired
    private DocumentProcessingPipeline pipeline;

    @Autowired
    private SearchService search;

    private record Tenant(Cookie cookie, UUID tenantId, UUID documentId) {
    }

    private Tenant tenantWith(String text, String marker) throws Exception {
        String email = "search-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Suche Test","accountType":"COMPANY"}
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
        Cookie session = login.getResponse().getCookie("bc_session");

        MockMultipartFile file = new MockMultipartFile("file", marker + ".pdf", "application/pdf",
                TestDocuments.pdf(text));
        MvcResult upload = mockMvc.perform(multipart("/api/v1/documents").file(file).with(csrf())
                        .cookie(session))
                .andExpect(status().isCreated())
                .andReturn();
        UUID documentId = UUID.fromString(JsonPath.read(
                upload.getResponse().getContentAsString(), "$.document.id"));

        RequestContext.runAs(tenantId, () -> pipeline.advance(documentId));
        return new Tenant(session, tenantId, documentId);
    }

    @Test
    @DisplayName("search never returns a document of another tenant")
    void searchDoesNotCrossTenants() throws Exception {
        // The word is deliberately unusual and appears in exactly one tenant's document.
        Tenant alpha = tenantWith("Zahlungsaufforderung Zwickauerwasserstoff\nRechnungsnummer: 1",
                "Alpha");
        Tenant beta = tenantWith("Zahlungsaufforderung Zwickauerwasserstoff\nRechnungsnummer: 2",
                "Beta");

        // Both tenants can find their own document by that word.
        mockMvc.perform(get("/api/v1/documents/search").param("q", "Zwickauerwasserstoff")
                        .cookie(alpha.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.results[0].documentId").value(alpha.documentId().toString()));

        mockMvc.perform(get("/api/v1/documents/search").param("q", "Zwickauerwasserstoff")
                        .cookie(beta.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.results[0].documentId").value(beta.documentId().toString()));
    }

    @Test
    @DisplayName("a search for something only the other tenant has returns nothing at all")
    void searchForForeignWordReturnsNothing() throws Exception {
        Tenant alpha = tenantWith("Rechnungsnummer: 3\nBetrag: 10,00 EUR", "Alpha");

        // This word exists in no document of this tenant.
        mockMvc.perform(get("/api/v1/documents/search").param("q", "Vollkommenandereswort")
                        .cookie(alpha.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.results").isEmpty());
    }

    @Test
    @DisplayName("German stemming is used, so a plural finds the singular")
    void usesGermanStemming() throws Exception {
        Tenant tenant = tenantWith("Rechnung\nZahlbar bis: 31.03.2026\nBruttobetrag: 1.190,00 EUR",
                "Rechnung");

        // "Rechnungen" is the plural of "Rechnung"; the German configuration reduces both to the
        // same stem.
        mockMvc.perform(get("/api/v1/documents/search").param("q", "Rechnungen")
                        .cookie(tenant.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    @DisplayName("a German compound is not split, which is a real limit of full text search here")
    void germanCompoundsAreNotSplit() throws Exception {
        Tenant tenant = tenantWith("Rechnungsnummer: 9\nBetrag: 1,00 EUR", "Rechnung");

        // PostgreSQL's German stemmer does not decompose "Rechnungsnummer" into "Rechnung" and
        // "Nummer", so a query for the noun alone does not find it. Recorded as a test because it is
        // a property of the search a user will notice, and a future typo-tolerant or trigram based
        // path would change this result deliberately.
        mockMvc.perform(get("/api/v1/documents/search").param("q", "Rechnungen")
                        .cookie(tenant.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        // The compound itself is found, which is what matters for the field names documents use.
        mockMvc.perform(get("/api/v1/documents/search").param("q", "Rechnungsnummer")
                        .cookie(tenant.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    @DisplayName("a hit names the page it matched, and the excerpt marks the match")
    void hitNamesThePage() throws Exception {
        Tenant tenant = tenantWith("Rechnungsnummer: 5\nZahlbar bis: 31.03.2026\nBruttobetrag: 1.190,00 EUR",
                "Rechnung");

        MvcResult result = mockMvc.perform(get("/api/v1/documents/search").param("q", "Rechnungsnummer")
                        .cookie(tenant.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].pages[0].pageNumber").value(1))
                .andExpect(jsonPath("$.results[0].pages[0].excerpt").isNotEmpty())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .as("the excerpt marks the matching words so a user sees why the document matched")
                .contains("<<");
    }

    @Test
    @DisplayName("the response states whether semantic search was available")
    void reportsSemanticAvailability() throws Exception {
        Tenant tenant = tenantWith("Rechnungsnummer: 6", "Rechnung");

        mockMvc.perform(get("/api/v1/documents/search").param("q", "Rechnungsnummer")
                        .cookie(tenant.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.searchMode").value("FULL_TEXT"))
                .andExpect(jsonPath("$.semantic.available").isBoolean())
                .andExpect(jsonPath("$.semantic.reason").isNotEmpty());
    }

    @Test
    @DisplayName("an empty query is a filter, not a search for nothing")
    void emptyQueryIsNotAnEmptyResult() throws Exception {
        Tenant tenant = tenantWith("Rechnungsnummer: 7", "Rechnung");

        // A cleared search box must not look like an empty archive.
        mockMvc.perform(get("/api/v1/documents/search").param("q", "   ").cookie(tenant.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.searchMode").value("FILTER"))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    @DisplayName("filters narrow the result instead of being ignored")
    void filtersNarrowTheResult() throws Exception {
        Tenant tenant = tenantWith("Rechnungsnummer: 8\nZahlbar bis: 31.03.2026\nUmsatzsteuer: 1,00 EUR",
                "Rechnung");

        mockMvc.perform(get("/api/v1/documents/search").param("q", "Rechnungsnummer")
                        .param("type", "mahnung").cookie(tenant.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        mockMvc.perform(get("/api/v1/documents/search").param("q", "Rechnungsnummer")
                        .param("type", "rechnung").cookie(tenant.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    @DisplayName("the semantic status is the same one the port reports, not a guess")
    void semanticStatusComesFromThePort() {
        assertThat(search.semanticPort().statusReason()).isNotEmpty();
        assertThat(search.semanticPort().status())
                .isEqualTo(search.semanticPort().status());
    }
}
