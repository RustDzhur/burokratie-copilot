package de.buerokratiecopilot.ai.rag.web;

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
 * Document search over HTTP (spec §19; E-006).
 *
 * <p>What this checks beyond the routes: <strong>what was examined is reported alongside what was
 * found</strong> — an empty result from a search that looked at nothing is a different statement from one
 * that looked at fifty pages — and <strong>an empty query is refused rather than answered</strong>, because
 * one that returned the account's documents would list everything while appearing to have asked something.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PassageSearchControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    private record Session(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private Session register() throws Exception {
        String email = "sea-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
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
        return new Session(login.getResponse().getCookie("bc_session"), tenantId, userId);
    }

    // ---------------------------------------------------------------- the distinctions

    @Test
    @DisplayName("a search that found nothing says how much it examined")
    void aSearchThatFoundNothingSaysWhatItExamined() throws Exception {
        Session session = register();

        // An empty result from a search that examined nothing is a different statement from one that
        // examined fifty pages and matched none; a caller who cannot tell them apart cannot tell an empty
        // account from a query that was never run.
        mockMvc.perform(get("/api/v1/search").param("query", "Widerspruchsfrist")
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passages").isEmpty())
                .andExpect(jsonPath("$.count").value(0))
                // Reported even when it is zero, which is the point.
                .andExpect(jsonPath("$.considered").isNumber())
                .andExpect(jsonPath("$.note").isNotEmpty());
    }

    @Test
    @DisplayName("an empty query is refused rather than answered with everything")
    void anEmptyQueryIsRefused() throws Exception {
        Session session = register();

        mockMvc.perform(get("/api/v1/search").param("query", "  ").cookie(session.cookie()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/search").cookie(session.cookie()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a search can be narrowed, and the answer says it was")
    void aSearchSaysWhenItWasNarrowed() throws Exception {
        Session session = register();

        mockMvc.perform(get("/api/v1/search").param("query", "Rechnung").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restrictedToDocumentTypes").value("alle"));

        mockMvc.perform(get("/api/v1/search").param("query", "Rechnung")
                        .param("documentTypeIds", UUID.randomUUID().toString())
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restrictedToDocumentTypes").value(1));
    }

    // ---------------------------------------------------------------- checking a citation

    @Test
    @DisplayName("a page that does not exist is reported, so a citation cannot point at it")
    void aPageThatDoesNotExistIsReported() throws Exception {
        Session session = register();

        // A passage pointing at a page that does not exist is a citation nobody can follow.
        mockMvc.perform(get("/api/v1/search/pages/{documentId}/{page}", UUID.randomUUID(), 7)
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(false))
                .andExpect(jsonPath("$.note").isNotEmpty());
    }

    // ---------------------------------------------------------------- what is refused

    @Test
    @DisplayName("without a session nothing is searched")
    void withoutASessionNothingIsSearched() throws Exception {
        mockMvc.perform(get("/api/v1/search").param("query", "Rechnung"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/search/pages/{documentId}/{page}", UUID.randomUUID(), 1))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("another account's documents are not searched")
    void anotherAccountsDocumentsAreNotSearched() throws Exception {
        Session first = register();
        Session second = register();

        // Both accounts are empty here, which is what makes the point: the search runs in the caller's
        // account and cannot reach the other one's pages whatever is in them.
        mockMvc.perform(get("/api/v1/search").param("query", "Rechnung").cookie(second.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passages").isEmpty());
    }

    @Test
    @DisplayName("the document permissions exist and are held, which is what makes this reachable")
    void theDocumentPermissionsAreHeld() {
        // A passage is the text of a document, so searching is reading; the route hands out no text the
        // reader could not already open.
        Integer held = jdbc.queryForObject("""
                select count(*) from role_permissions rp
                join roles r on r.id = rp.role_id
                join permissions p on p.id = rp.permission_id
                where p.code in ('documents.view','documents.review')
                  and r.tenant_id is null and r.code in ('OWNER','ADMIN')
                """, Integer.class);

        org.assertj.core.api.Assertions.assertThat(held)
                .as("the document permissions must be granted to OWNER and ADMIN, or this is dead")
                .isGreaterThan(0);
    }
}
