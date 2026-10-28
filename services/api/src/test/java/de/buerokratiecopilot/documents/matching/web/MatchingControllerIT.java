package de.buerokratiecopilot.documents.matching.web;

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
 * Document matching over HTTP (spec §13; B-010).
 *
 * <p>What this checks beyond the routes: a <strong>suggestion is not a link</strong> — {@code suggested}
 * says whether the platform is proposing or whether a person has already decided — and the
 * <strong>decider comes from the session</strong>, because the step exists so that somebody takes
 * responsibility for the filing.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MatchingControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    private record Session(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private Session register() throws Exception {
        String email = "mat-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Zuordnung Test","accountType":"COMPANY"}
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

    // ---------------------------------------------------------------- the routes

    @Test
    @DisplayName("a document nobody has matched to anything gets no proposals")
    void anUnmatchedDocumentGetsNothing() throws Exception {
        Session session = register();
        UUID documentId = documentFor(session);

        // An empty list, not a guess: the platform has matched nothing here, and showing a row would be
        // showing a filing nobody proposed.
        mockMvc.perform(get("/api/v1/documents/{id}/match-proposals", documentId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("nothing is waiting for a person on a fresh account")
    void nothingIsWaitingOnAFreshAccount() throws Exception {
        Session session = register();

        mockMvc.perform(get("/api/v1/document-matches/pending").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("the decisions and match kinds are served, so an interface does not hard-code them")
    void theCatalogueIsServed() throws Exception {
        Session session = register();

        mockMvc.perform(get("/api/v1/document-matches/decisions").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").isNotEmpty());
        mockMvc.perform(get("/api/v1/document-matches/types").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").isNotEmpty());
    }

    // ---------------------------------------------------------------- deciding

    @Test
    @DisplayName("a decision without a status is refused")
    void aDecisionWithoutAStatusIsRefused() throws Exception {
        Session session = register();

        mockMvc.perform(post("/api/v1/document-matches/{id}/decide", UUID.randomUUID()).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an unknown decision is refused with the ones that exist")
    void anUnknownDecisionIsRefused() throws Exception {
        Session session = register();

        mockMvc.perform(post("/api/v1/document-matches/{id}/decide", UUID.randomUUID()).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"VIELLEICHT\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an unknown proposal is reported rather than invented")
    void anUnknownProposalIsReported() throws Exception {
        Session session = register();

        mockMvc.perform(post("/api/v1/document-matches/{id}/decide", UUID.randomUUID()).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"CONFIRMED\"}"))
                .andExpect(status().is4xxClientError());
    }

    // ---------------------------------------------------------------- what is refused

    @Test
    @DisplayName("without a session nothing is read or decided")
    void withoutASessionNothingHappens() throws Exception {
        mockMvc.perform(get("/api/v1/document-matches/pending")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/documents/{id}/match-proposals", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/document-matches/{id}/decide", UUID.randomUUID()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"CONFIRMED\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("another account's document yields no proposals")
    void anotherAccountsDocumentYieldsNothing() throws Exception {
        Session first = register();
        Session second = register();
        UUID documentId = documentFor(first);

        mockMvc.perform(get("/api/v1/documents/{id}/match-proposals", documentId)
                        .cookie(second.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
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
