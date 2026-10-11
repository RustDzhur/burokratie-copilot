package de.buerokratiecopilot.identity.access.web;

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
 * Invitations, members and case access over HTTP (spec §4, §20).
 *
 * <p>{@code InvitationService} was implemented and tested with no endpoint, so nobody could be invited to
 * anything. What this checks beyond the routes is the lesson from {@code taxonomy.manage}: the permissions
 * named here must be ones that <strong>exist and are granted</strong>, because a permission nobody holds
 * makes every endpoint answer 403 and a service test cannot see it.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccessControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    private record Session(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private Session register() throws Exception {
        String email = "acc-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Zugriff Test","accountType":"COMPANY"}
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

    private String invite(Session session) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/invitations").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"neu-%s@example.de","message":"Willkommen"}
                                """.formatted(UUID.randomUUID().toString().substring(0, 8))))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(created.getResponse().getContentAsString(), "$.token");
    }

    // ---------------------------------------------------------------- the permission exists

    @Test
    @DisplayName("the permission these endpoints require is one a role actually holds")
    void theRequiredPermissionIsGranted() {
        // The lesson from taxonomy.manage: a permission in the catalogue that no role holds makes every
        // endpoint answer 403, and a service test cannot see it because it never asks about roles.
        Integer held = jdbc.queryForObject("""
                select count(*) from role_permissions rp
                join roles r on r.id = rp.role_id
                join permissions p on p.id = rp.permission_id
                where p.code = 'settings.edit' and r.tenant_id is null and r.code in ('OWNER','ADMIN')
                """, Integer.class);

        org.assertj.core.api.Assertions.assertThat(held)
                .as("settings.edit must be granted to OWNER and ADMIN, or these endpoints are unreachable")
                .isEqualTo(2);
    }

    // ---------------------------------------------------------------- invitations

    @Test
    @DisplayName("an invitation is created and its token is shown once")
    void anInvitationIsCreated() throws Exception {
        Session session = register();

        MvcResult created = mockMvc.perform(post("/api/v1/invitations").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"neu-%s@example.de","message":"Willkommen"}
                                """.formatted(UUID.randomUUID().toString().substring(0, 8))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.invitationId").isNotEmpty())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.note").isNotEmpty())
                .andReturn();

        String token = JsonPath.read(created.getResponse().getContentAsString(), "$.token");
        String stored = jdbc.queryForObject("""
                select token_hash from invitations where tenant_id = ?
                """, String.class, session.tenantId());
        // Stored only as its hash: whoever holds the token can join the account.
        org.assertj.core.api.Assertions.assertThat(stored).doesNotContain(token);
    }

    @Test
    @DisplayName("the open invitations are listed without their tokens")
    void openInvitationsAreListed() throws Exception {
        Session session = register();
        String token = invite(session);

        MvcResult listed = mockMvc.perform(get("/api/v1/invitations").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].email").isNotEmpty())
                .andReturn();

        org.assertj.core.api.Assertions.assertThat(listed.getResponse().getContentAsString())
                .doesNotContain(token);
    }

    @Test
    @DisplayName("an invitation can be withdrawn over HTTP")
    void anInvitationCanBeWithdrawn() throws Exception {
        Session session = register();
        invite(session);
        UUID invitationId = UUID.fromString(jdbc.queryForObject("""
                select id from invitations where tenant_id = ? and status = 'PENDING'
                """, String.class, session.tenantId()));

        mockMvc.perform(post("/api/v1/invitations/{id}/revoke", invitationId).with(csrf())
                        .cookie(session.cookie()))
                .andExpect(status().isNoContent());

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("""
                select status from invitations where id = ?
                """, String.class, invitationId)).isEqualTo("REVOKED");
    }

    @Test
    @DisplayName("inviting the same address twice is refused")
    void invitingTwiceIsRefused() throws Exception {
        Session session = register();
        String email = "doppelt-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        mockMvc.perform(post("/api/v1/invitations").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\"}".formatted(email)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/invitations").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\"}".formatted(email)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("overdue invitations are closed, and the answer counts them")
    void overdueInvitationsAreClosed() throws Exception {
        Session session = register();

        mockMvc.perform(post("/api/v1/invitations/expire-overdue").with(csrf())
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expired").isNumber());
    }

    // ---------------------------------------------------------------- members

    @Test
    @DisplayName("the members of the account are served by name")
    void membersAreServed() throws Exception {
        Session session = register();

        mockMvc.perform(get("/api/v1/members").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].display_name").isNotEmpty())
                .andExpect(jsonPath("$[0].user_id").isNotEmpty());
    }

    @Test
    @DisplayName("a member can be suspended over HTTP")
    void aMemberCanBeSuspended() throws Exception {
        Session session = register();

        mockMvc.perform(post("/api/v1/members/{id}/suspend", session.userId()).with(csrf())
                        .cookie(session.cookie()))
                .andExpect(status().isNoContent());

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("""
                select status from memberships where tenant_id = ? and user_id = ?
                """, String.class, session.tenantId(), session.userId())).isEqualTo("SUSPENDED");
    }

    // ---------------------------------------------------------------- case access

    @Test
    @DisplayName("access to one case can be granted and taken away")
    void caseAccessCanBeGrantedAndTaken() throws Exception {
        Session session = register();
        UUID caseId = jdbc.queryForObject("""
                insert into cases (id, tenant_id, reference, title, status)
                values (gen_random_uuid(), ?, ?, 'Ein Verfahren', 'NEW') returning id
                """, UUID.class, session.tenantId(), "AZ-" + UUID.randomUUID().toString().substring(0, 8));

        mockMvc.perform(post("/api/v1/cases/{id}/access", caseId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"%s\",\"role\":\"PARTICIPANT\",\"accessLevel\":\"WRITE\"}"
                                .formatted(session.userId())))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/cases/{id}/access/{userId}", caseId, session.userId())
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mayRead").value(true));

        mockMvc.perform(post("/api/v1/cases/{id}/access/revoke", caseId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"%s\"}".formatted(session.userId())))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/cases/{id}/access/{userId}", caseId, session.userId())
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mayRead").value(false));
    }

    @Test
    @DisplayName("an unknown role is refused with a sentence")
    void anUnknownRoleIsRefused() throws Exception {
        Session session = register();
        UUID caseId = jdbc.queryForObject("""
                insert into cases (id, tenant_id, reference, title, status)
                values (gen_random_uuid(), ?, ?, 'Ein Verfahren', 'NEW') returning id
                """, UUID.class, session.tenantId(), "AZ-" + UUID.randomUUID().toString().substring(0, 8));

        mockMvc.perform(post("/api/v1/cases/{id}/access", caseId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"%s\",\"role\":\"GODMODE\",\"accessLevel\":\"READ\"}"
                                .formatted(session.userId())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("how somebody came to have access is served")
    void accessHistoryIsServed() throws Exception {
        Session session = register();

        mockMvc.perform(get("/api/v1/members/{id}/access-history", session.userId())
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // ---------------------------------------------------------------- what is refused

    @Test
    @DisplayName("without a session nothing is read or changed")
    void withoutASessionNothingHappens() throws Exception {
        mockMvc.perform(get("/api/v1/members")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/invitations")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/invitations").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"x@example.de\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/invitations/accept").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"bcinv_x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an invitation without an address is refused")
    void anInvitationWithoutAnAddressIsRefused() throws Exception {
        Session session = register();

        mockMvc.perform(post("/api/v1/invitations").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Ohne Adresse\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("revoking an unknown invitation is reported")
    void revokingAnUnknownInvitationIsReported() throws Exception {
        Session session = register();

        mockMvc.perform(post("/api/v1/invitations/{id}/revoke", UUID.randomUUID()).with(csrf())
                        .cookie(session.cookie()))
                .andExpect(status().is4xxClientError());
    }
}
