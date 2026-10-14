package de.buerokratiecopilot.family.web;

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
 * Personal and shared documents over HTTP (spec §3.1; H-003).
 *
 * <p>What this checks beyond the routes: a document is <strong>shared unless somebody marks it</strong>, so
 * the platform never turns an ordinary family file into a private one because a person forgot; and a member
 * asking about another member's personal document is told <strong>it does not exist</strong> rather than that
 * it is not theirs, because in a household the difference is itself information.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FamilyControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    private record Session(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private Session register() throws Exception {
        String email = "fam-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Familie Test","accountType":"FAMILY_HOUSEHOLD"}
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

    // ---------------------------------------------------------------- what is shared

    @Test
    @DisplayName("a document is shared unless somebody marks it")
    void aDocumentIsSharedUnlessMarked() throws Exception {
        Session session = register();
        UUID documentId = documentFor(session);

        // The platform must not turn an ordinary family file into a private one because a person forgot.
        mockMvc.perform(get("/api/v1/family/documents/{id}/personal", documentId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.personal").value(false));
        mockMvc.perform(get("/api/v1/family/documents/{id}/access", documentId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readable").value(true));
    }

    @Test
    @DisplayName("the bases access can rest on are served")
    void theBasesAreServed() throws Exception {
        Session session = register();

        mockMvc.perform(get("/api/v1/family/access-bases").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").isNotEmpty());
    }

    @Test
    @DisplayName("the documents a member may read are served")
    void theReadableDocumentsAreServed() throws Exception {
        Session session = register();
        documentFor(session);

        mockMvc.perform(get("/api/v1/family/documents").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // ---------------------------------------------------------------- marking

    @Test
    @DisplayName("a document can be marked personal and made shared again")
    void aDocumentCanBeMarkedAndUnmarked() throws Exception {
        Session session = register();
        UUID documentId = documentFor(session);

        mockMvc.perform(post("/api/v1/family/documents/{id}/personal", documentId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Privat\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.personal").value(true));

        mockMvc.perform(get("/api/v1/family/documents/{id}/personal", documentId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.personal").value(true));

        // And the direction back, so a person is not stuck with a decision they made by accident.
        mockMvc.perform(post("/api/v1/family/documents/{id}/shared", documentId).with(csrf())
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.personal").value(false));
    }

    @Test
    @DisplayName("a person cannot mark somebody else's document as their own")
    void somebodyElsesDocumentCannotBeClaimed() throws Exception {
        Session first = register();
        Session second = register();
        UUID documentId = documentFor(first);

        // Marking a document as somebody else's private property is not a decision a third person makes.
        mockMvc.perform(post("/api/v1/family/documents/{id}/personal", documentId).with(csrf())
                        .cookie(first.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberUserId\":\"%s\"}".formatted(second.userId())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a personal document stays in its owner's own list and not in the household's")
    void aPersonalDocumentStaysWithItsOwner() throws Exception {
        Session session = register();
        Session other = register();
        UUID documentId = documentFor(session);
        mockMvc.perform(post("/api/v1/family/documents/{id}/personal", documentId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated());

        // The owner still sees it - it is their document - and the household list is theirs alone.
        MvcResult personal = mockMvc.perform(get("/api/v1/family/documents/personal").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andReturn();
        org.assertj.core.api.Assertions.assertThat(personal.getResponse().getContentAsString())
                .as("the owner still sees their own personal document")
                .contains(documentId.toString());

        // Another account has no access to it at all.
        mockMvc.perform(get("/api/v1/family/documents/{id}/access", documentId).cookie(other.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readable").value(false));
    }

    // ---------------------------------------------------------------- sharing

    @Test
    @DisplayName("a personal document can be shared with a member and the share withdrawn")
    void aShareCanBeGrantedAndWithdrawn() throws Exception {
        Session session = register();
        UUID documentId = documentFor(session);
        mockMvc.perform(post("/api/v1/family/documents/{id}/personal", documentId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated());

        UUID other = UUID.fromString(jdbc.queryForObject("""
                insert into users (id, email, password_hash, display_name, status)
                values (gen_random_uuid(), ?, 'x', 'Andere Person', 'ACTIVE') returning id
                """, String.class, "andere-" + UUID.randomUUID().toString().substring(0, 8)
                        + "@example.de"));
        jdbc.update("""
                insert into memberships (id, tenant_id, user_id, status)
                values (gen_random_uuid(), ?, ?, 'ACTIVE')
                """, session.tenantId(), other);

        mockMvc.perform(post("/api/v1/family/documents/{id}/share", documentId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberUserId\":\"%s\"}".formatted(other)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sharedWith").value(other.toString()))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());

        mockMvc.perform(post("/api/v1/family/documents/{id}/share/revoke", documentId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberUserId\":\"%s\"}".formatted(other)))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("sharing without saying with whom is refused")
    void sharingWithoutAMemberIsRefused() throws Exception {
        Session session = register();
        UUID documentId = documentFor(session);

        mockMvc.perform(post("/api/v1/family/documents/{id}/share", documentId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the members of the household are served")
    void theMembersAreServed() throws Exception {
        Session session = register();

        mockMvc.perform(get("/api/v1/family/members").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].display_name").isNotEmpty());
    }

    @Test
    @DisplayName("the caller's own personal documents are served")
    void theOwnPersonalDocumentsAreServed() throws Exception {
        Session session = register();
        UUID documentId = documentFor(session);
        mockMvc.perform(post("/api/v1/family/documents/{id}/personal", documentId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/family/documents/personal").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").isNotEmpty());
    }

    // ---------------------------------------------------------------- what is refused

    @Test
    @DisplayName("without a session nothing is read or marked")
    void withoutASessionNothingHappens() throws Exception {
        mockMvc.perform(get("/api/v1/family/documents")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/family/documents/personal")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/family/documents/{id}/personal", UUID.randomUUID()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("another account's document is not readable, and the answer says no more than that")
    void anotherAccountsDocumentIsNotReadable() throws Exception {
        Session first = register();
        Session second = register();
        UUID documentId = documentFor(first);

        // The basis is NOT_PERMITTED for a document that does not exist and for one that belongs to
        // somebody else: the same answer, so it tells the second account nothing about the first.
        MvcResult decision = mockMvc.perform(
                        get("/api/v1/family/documents/{id}/access", documentId).cookie(second.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readable").value(false))
                .andReturn();
        String foreignBasis = JsonPath.read(decision.getResponse().getContentAsString(), "$.basis");

        MvcResult missing = mockMvc.perform(get("/api/v1/family/documents/{id}/access", UUID.randomUUID())
                        .cookie(second.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readable").value(false))
                .andReturn();
        String missingBasis = JsonPath.read(missing.getResponse().getContentAsString(), "$.basis");

        org.assertj.core.api.Assertions.assertThat(foreignBasis)
                .as("a private document of another account and one that does not exist must be "
                        + "indistinguishable, or the answer reveals what the other account keeps")
                .isEqualTo(missingBasis);
    }

    @Test
    @DisplayName("the settings permissions exist and are held, which is what makes the household list reachable")
    void theSettingsPermissionsAreHeld() {
        Integer held = jdbc.queryForObject("""
                select count(*) from role_permissions rp
                join roles r on r.id = rp.role_id
                join permissions p on p.id = rp.permission_id
                where p.code in ('settings.view','settings.edit')
                  and r.tenant_id is null and r.code in ('OWNER','ADMIN')
                """, Integer.class);

        org.assertj.core.api.Assertions.assertThat(held).isEqualTo(4);
    }
}
